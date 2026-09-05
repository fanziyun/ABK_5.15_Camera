// SPDX-License-Identifier: GPL-2.0
/*
 * ABK UVC camera coordination state for Android/Linux 5.15.
 *
 * This is deliberately not a camera sensor or encoder driver.  Camera2 and
 * the UVC V4L2 output queue remain in userspace.  The kernel side provides a
 * small, root-readable control plane and a configfs observation hook which is
 * safe to compose with the ABK FIDO hook.
 */

#include <linux/ctype.h>
#include <linux/configfs.h>
#include <linux/err.h>
#include <linux/kernel.h>
#include <linux/kobject.h>
#include <linux/list.h>
#include <linux/module.h>
#include <linux/mutex.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/sysfs.h>
#include <linux/usb/composite.h>

#include <linux/abk_uvc_camera.h>

#define ABK_UVC_CAMERA_MAX_VALUE 64
#define ABK_UVC_CAMERA_MAX_ERROR 192
#define ABK_UVC_CAMERA_MAX_TRACE 256

struct abk_uvc_camera_state {
	struct kobject *kobj;
	struct mutex lock;
	bool enabled;
	char mode[ABK_UVC_CAMERA_MAX_VALUE];
	char camera[ABK_UVC_CAMERA_MAX_VALUE];
	char profile[ABK_UVC_CAMERA_MAX_VALUE];
	char backend[ABK_UVC_CAMERA_MAX_VALUE];
	char stream_state[ABK_UVC_CAMERA_MAX_VALUE];
	char usb_state[ABK_UVC_CAMERA_MAX_VALUE];
	char fido_state[ABK_UVC_CAMERA_MAX_VALUE];
	char last_error[ABK_UVC_CAMERA_MAX_ERROR];
	char last_trace[ABK_UVC_CAMERA_MAX_TRACE];
};

static struct abk_uvc_camera_state abk_uvc_camera;

struct abk_uvc_injected_func {
	struct list_head node;
	struct list_head *owner;
	struct usb_function *f;
};

static LIST_HEAD(abk_uvc_injected_funcs);
static DEFINE_MUTEX(abk_uvc_injected_lock);
static const char abk_uvc_supported_profiles[] =
	"yuy2_640x360\n"
	"yuy2_1280x720\n"
	"mjpeg_640x360\n"
	"mjpeg_1280x720\n"
	"mjpeg_1920x1080_30 experimental\n"
	"mjpeg_1920x1080_60 experimental\n"
	"mjpeg_3840x2160_15 experimental\n"
	"mjpeg_3840x2160_30 experimental\n"
	"h264_1920x1080_60 experimental\n"
	"h264_3840x2160_30 experimental\n"
	"h264_3840x2160_60 experimental\n";

static const char *abk_uvc_trimmed(const char *src, char *dst, size_t size)
{
	char *value;

	if (!src || !dst || !size)
		return NULL;
	if (strscpy(dst, src, size) < 0)
		return NULL;
	value = strim(dst);
	if (value != dst)
		memmove(dst, value, strlen(value) + 1);
	return dst;
}

static void abk_uvc_set_locked(char *dst, size_t size, const char *value)
{
	strscpy(dst, value, size);
}

static void abk_uvc_trace_locked(const char *event)
{
	snprintf(abk_uvc_camera.last_trace,
		 sizeof(abk_uvc_camera.last_trace),
		 "jiffies=%lu %s", jiffies, event);
}

static void abk_uvc_error_locked(const char *error)
{
	strscpy(abk_uvc_camera.last_error, error,
		 sizeof(abk_uvc_camera.last_error));
	abk_uvc_trace_locked(error);
}

static bool abk_uvc_valid_profile(const char *profile)
{
	static const char * const profiles[] = {
		"yuy2_640x360",
		"yuy2_1280x720",
		"mjpeg_640x360",
		"mjpeg_1280x720",
		"mjpeg_1920x1080_30",
		"mjpeg_1920x1080_60",
		"mjpeg_3840x2160_15",
		"mjpeg_3840x2160_30",
		"h264_1920x1080_60",
		"h264_3840x2160_30",
		"h264_3840x2160_60",
	};
	int i;

	for (i = 0; i < ARRAY_SIZE(profiles); ++i) {
		if (!strcmp(profile, profiles[i]))
			return true;
	}
	return false;
}

static bool abk_uvc_valid_value(const char *value)
{
	const unsigned char *p = (const unsigned char *)value;

	if (!value || !*value)
		return false;
	while (*p) {
		if (!isalnum(*p) && *p != '_' && *p != '-' && *p != '.')
			return false;
		++p;
	}
	return true;
}

static bool abk_uvc_has_function(struct list_head *func_list,
				 const char *needle)
{
	struct usb_function *f;

	if (!func_list)
		return false;
	list_for_each_entry(f, func_list, list) {
		if (f->name && strnstr(f->name, needle, 32))
			return true;
	}
	return false;
}

static ssize_t enabled_show(struct kobject *kobj,
				struct kobj_attribute *attr, char *buf)
{
	ssize_t ret;

	mutex_lock(&abk_uvc_camera.lock);
	ret = sysfs_emit(buf, "%u\n", abk_uvc_camera.enabled ? 1 : 0);
	mutex_unlock(&abk_uvc_camera.lock);
	return ret;
}

static ssize_t enabled_store(struct kobject *kobj,
				 struct kobj_attribute *attr,
				 const char *buf, size_t count)
{
	bool value;

	if (kstrtobool(buf, &value))
		return -EINVAL;
	mutex_lock(&abk_uvc_camera.lock);
	abk_uvc_camera.enabled = value;
	abk_uvc_set_locked(abk_uvc_camera.stream_state,
			   sizeof(abk_uvc_camera.stream_state),
			   value ? "ready" : "stopped");
	abk_uvc_trace_locked(value ? "enabled" : "disabled");
	mutex_unlock(&abk_uvc_camera.lock);
	return count;
}

#define ABK_UVC_TEXT_SHOW(_name, _field) \
static ssize_t _name##_show(struct kobject *kobj, \
			    struct kobj_attribute *attr, char *buf) \
{ \
	ssize_t ret; \
	mutex_lock(&abk_uvc_camera.lock); \
	ret = sysfs_emit(buf, "%s\n", abk_uvc_camera._field); \
	mutex_unlock(&abk_uvc_camera.lock); \
	return ret; \
}

ABK_UVC_TEXT_SHOW(mode, mode)
ABK_UVC_TEXT_SHOW(camera, camera)
ABK_UVC_TEXT_SHOW(profile, profile)
ABK_UVC_TEXT_SHOW(backend, backend)
ABK_UVC_TEXT_SHOW(stream_state, stream_state)
ABK_UVC_TEXT_SHOW(usb_state, usb_state)
ABK_UVC_TEXT_SHOW(fido_state, fido_state)
ABK_UVC_TEXT_SHOW(last_error, last_error)
ABK_UVC_TEXT_SHOW(last_trace, last_trace)

static ssize_t mode_store(struct kobject *kobj,
			  struct kobj_attribute *attr,
			  const char *buf, size_t count)
{
	char value[ABK_UVC_CAMERA_MAX_VALUE];

	if (!abk_uvc_trimmed(buf, value, sizeof(value)))
		return -EINVAL;
	if (strcmp(value, "manual") && strcmp(value, "auto") &&
	    strcmp(value, "disabled"))
		return -EINVAL;
	mutex_lock(&abk_uvc_camera.lock);
	abk_uvc_set_locked(abk_uvc_camera.mode,
			   sizeof(abk_uvc_camera.mode), value);
	abk_uvc_trace_locked("mode_changed");
	mutex_unlock(&abk_uvc_camera.lock);
	return count;
}

static ssize_t camera_store(struct kobject *kobj,
			    struct kobj_attribute *attr,
			    const char *buf, size_t count)
{
	char value[ABK_UVC_CAMERA_MAX_VALUE];

	if (!abk_uvc_trimmed(buf, value, sizeof(value)))
		return -EINVAL;
	if (strcmp(value, "front") && strcmp(value, "back"))
		return -EINVAL;
	mutex_lock(&abk_uvc_camera.lock);
	abk_uvc_set_locked(abk_uvc_camera.camera,
			   sizeof(abk_uvc_camera.camera), value);
	abk_uvc_trace_locked("camera_changed");
	mutex_unlock(&abk_uvc_camera.lock);
	return count;
}

static ssize_t profile_store(struct kobject *kobj,
			     struct kobj_attribute *attr,
			     const char *buf, size_t count)
{
	char value[ABK_UVC_CAMERA_MAX_VALUE];

	if (!abk_uvc_trimmed(buf, value, sizeof(value)) ||
	    !abk_uvc_valid_profile(value))
		return -EINVAL;
	mutex_lock(&abk_uvc_camera.lock);
	abk_uvc_set_locked(abk_uvc_camera.profile,
			   sizeof(abk_uvc_camera.profile), value);
	abk_uvc_trace_locked("profile_changed");
	mutex_unlock(&abk_uvc_camera.lock);
	return count;
}

static ssize_t backend_store(struct kobject *kobj,
			     struct kobj_attribute *attr,
			     const char *buf, size_t count)
{
	char value[ABK_UVC_CAMERA_MAX_VALUE];

	if (!abk_uvc_trimmed(buf, value, sizeof(value)) ||
	    (strcmp(value, "none") && strcmp(value, "test_pattern") &&
	     strcmp(value, "device_as_webcam") && strcmp(value, "camera2")))
		return -EINVAL;
	mutex_lock(&abk_uvc_camera.lock);
	abk_uvc_set_locked(abk_uvc_camera.backend,
			   sizeof(abk_uvc_camera.backend), value);
	abk_uvc_trace_locked("backend_changed");
	mutex_unlock(&abk_uvc_camera.lock);
	return count;
}

static ssize_t supported_profiles_show(struct kobject *kobj,
				       struct kobj_attribute *attr, char *buf)
{
	return sysfs_emit(buf, "%s", abk_uvc_supported_profiles);
}

static int abk_uvc_command_locked(const char *command)
{
	char value[128];
	char *argument;

	if (!abk_uvc_trimmed(command, value, sizeof(value)))
		return -EINVAL;
	if (!strcmp(value, "start")) {
		abk_uvc_camera.enabled = true;
		abk_uvc_set_locked(abk_uvc_camera.stream_state,
				   sizeof(abk_uvc_camera.stream_state), "starting");
		abk_uvc_camera.last_error[0] = '\0';
		abk_uvc_trace_locked("command_start");
		return 0;
	}
	if (!strcmp(value, "stop")) {
		abk_uvc_camera.enabled = false;
		abk_uvc_set_locked(abk_uvc_camera.stream_state,
				   sizeof(abk_uvc_camera.stream_state), "stopped");
		abk_uvc_trace_locked("command_stop");
		return 0;
	}
	if (!strcmp(value, "restore_usb")) {
		abk_uvc_set_locked(abk_uvc_camera.usb_state,
				   sizeof(abk_uvc_camera.usb_state), "restore_requested");
		abk_uvc_trace_locked("command_restore_usb");
		return 0;
	}
	if (!strcmp(value, "test_pattern on")) {
		abk_uvc_camera.enabled = true;
		abk_uvc_set_locked(abk_uvc_camera.backend,
				   sizeof(abk_uvc_camera.backend), "test_pattern");
		abk_uvc_set_locked(abk_uvc_camera.stream_state,
				   sizeof(abk_uvc_camera.stream_state), "test_pattern");
		abk_uvc_trace_locked("test_pattern_on");
		return 0;
	}
	if (!strcmp(value, "test_pattern off")) {
		abk_uvc_set_locked(abk_uvc_camera.backend,
				   sizeof(abk_uvc_camera.backend), "none");
		abk_uvc_set_locked(abk_uvc_camera.stream_state,
				   sizeof(abk_uvc_camera.stream_state), "ready");
		abk_uvc_trace_locked("test_pattern_off");
		return 0;
	}
	if (!strncmp(value, "set_camera ", 11)) {
		argument = value + 11;
		if (strcmp(argument, "front") && strcmp(argument, "back"))
			return -EINVAL;
		abk_uvc_set_locked(abk_uvc_camera.camera,
				   sizeof(abk_uvc_camera.camera), argument);
		abk_uvc_trace_locked("command_set_camera");
		return 0;
	}
	if (!strncmp(value, "set_profile ", 12)) {
		argument = value + 12;
		if (!abk_uvc_valid_profile(argument))
			return -EINVAL;
		abk_uvc_set_locked(abk_uvc_camera.profile,
				   sizeof(abk_uvc_camera.profile), argument);
		abk_uvc_trace_locked("command_set_profile");
		return 0;
	}
	if (!abk_uvc_valid_value(value))
		return -EINVAL;
	return -EINVAL;
}

static ssize_t command_store(struct kobject *kobj,
			     struct kobj_attribute *attr,
			     const char *buf, size_t count)
{
	int ret;

	mutex_lock(&abk_uvc_camera.lock);
	ret = abk_uvc_command_locked(buf);
	if (ret)
		abk_uvc_error_locked("invalid command");
	mutex_unlock(&abk_uvc_camera.lock);
	return ret ? ret : count;
}

static struct kobj_attribute enabled_attr = __ATTR_RW(enabled);
static struct kobj_attribute mode_attr = __ATTR_RW(mode);
static struct kobj_attribute camera_attr = __ATTR_RW(camera);
static struct kobj_attribute profile_attr = __ATTR_RW(profile);
static struct kobj_attribute backend_attr = __ATTR_RW(backend);
static struct kobj_attribute stream_state_attr = __ATTR_RO(stream_state);
static struct kobj_attribute usb_state_attr = __ATTR_RO(usb_state);
static struct kobj_attribute fido_state_attr = __ATTR_RO(fido_state);
static struct kobj_attribute supported_profiles_attr = __ATTR_RO(supported_profiles);
static struct kobj_attribute last_error_attr = __ATTR_RO(last_error);
static struct kobj_attribute last_trace_attr = __ATTR_RO(last_trace);
static struct kobj_attribute command_attr = __ATTR_WO(command);

static struct attribute *abk_uvc_attrs[] = {
	&enabled_attr.attr,
	&mode_attr.attr,
	&camera_attr.attr,
	&profile_attr.attr,
	&backend_attr.attr,
	&stream_state_attr.attr,
	&usb_state_attr.attr,
	&fido_state_attr.attr,
	&supported_profiles_attr.attr,
	&last_error_attr.attr,
	&last_trace_attr.attr,
	&command_attr.attr,
	NULL,
};

static const struct attribute_group abk_uvc_attr_group = {
	.attrs = abk_uvc_attrs,
};

static struct usb_function_instance *
abk_uvc_find_instance(struct list_head *available_func)
{
	struct usb_function_instance *fi;

	if (!available_func)
		return NULL;
	list_for_each_entry(fi, available_func, cfs_list) {
		const char *name = config_item_name(&fi->group.cg_item);
		if (name && !strcmp(name, "uvc.0"))
			return fi;
	}
	return NULL;
}

static int abk_uvc_inject(struct list_head *func_list,
			  struct list_head *available_func)
{
	struct usb_function_instance *fi;
	struct usb_function *f;
	struct abk_uvc_injected_func *node;

	fi = abk_uvc_find_instance(available_func);
	if (!fi)
		return -ENOENT;

	f = usb_get_function(fi);
	if (IS_ERR(f))
		return PTR_ERR(f);

	node = kmalloc(sizeof(*node), GFP_KERNEL);
	if (!node) {
		usb_put_function(f);
		return -ENOMEM;
	}

	node->f = f;
	node->owner = func_list;

	mutex_lock(&abk_uvc_injected_lock);
	list_add_tail(&f->list, func_list);
	list_add_tail(&node->node, &abk_uvc_injected_funcs);
	mutex_unlock(&abk_uvc_injected_lock);

	return 0;
}

void abk_uvc_camera_drop_injected(struct list_head *func_list)
{
	struct abk_uvc_injected_func *node, *tmp;

	if (!func_list)
		return;

	mutex_lock(&abk_uvc_injected_lock);
	list_for_each_entry_safe(node, tmp, &abk_uvc_injected_funcs, node) {
		if (node->owner != func_list)
			continue;
		if (!list_empty(&node->f->list))
			list_del_init(&node->f->list);
		usb_put_function(node->f);
		list_del(&node->node);
		kfree(node);
	}
	mutex_unlock(&abk_uvc_injected_lock);
}
EXPORT_SYMBOL_GPL(abk_uvc_camera_drop_injected);

int abk_uvc_camera_prepare_config(struct usb_composite_dev *cdev,
				  struct usb_configuration *cfg,
				  struct list_head *func_list,
				  struct list_head *available_func)
{
	bool has_uvc;
	bool has_fido;
	int ret;

	if (!cdev || !cfg || !func_list || !available_func)
		return -EINVAL;

	has_uvc = abk_uvc_has_function(func_list, "uvc");
	has_fido = abk_uvc_has_function(func_list, "abk_fido");

	mutex_lock(&abk_uvc_camera.lock);
	if (abk_uvc_camera.enabled && !has_uvc) {
		mutex_unlock(&abk_uvc_camera.lock);
		ret = abk_uvc_inject(func_list, available_func);
		mutex_lock(&abk_uvc_camera.lock);
		if (ret) {
			abk_uvc_error_locked("uvc_inject_failed");
		} else {
			has_uvc = abk_uvc_has_function(func_list, "uvc");
		}
	}

	if (!abk_uvc_camera.enabled) {
		abk_uvc_set_locked(abk_uvc_camera.usb_state,
				   sizeof(abk_uvc_camera.usb_state), "camera_disabled");
	} else if (has_uvc) {
		abk_uvc_set_locked(abk_uvc_camera.usb_state,
				   sizeof(abk_uvc_camera.usb_state), "uvc_configured");
		abk_uvc_set_locked(abk_uvc_camera.stream_state,
				   sizeof(abk_uvc_camera.stream_state), "ready");
		abk_uvc_camera.last_error[0] = '\0';
	} else {
		abk_uvc_set_locked(abk_uvc_camera.usb_state,
				   sizeof(abk_uvc_camera.usb_state), "no_uvc_function");
		abk_uvc_error_locked("enabled but config has no UVC function");
	}
	abk_uvc_set_locked(abk_uvc_camera.fido_state,
			   sizeof(abk_uvc_camera.fido_state),
			   has_fido ? "present" : "not_present");
	abk_uvc_trace_locked(has_uvc ? "config_prepared_uvc" : "config_prepared_without_uvc");
	mutex_unlock(&abk_uvc_camera.lock);
	return 0;
}
EXPORT_SYMBOL_GPL(abk_uvc_camera_prepare_config);

void abk_uvc_camera_release_config(struct list_head *func_list)
{
	bool has_fido;

	if (!func_list)
		return;
	has_fido = abk_uvc_has_function(func_list, "abk_fido");
	mutex_lock(&abk_uvc_camera.lock);
	abk_uvc_set_locked(abk_uvc_camera.usb_state,
			   sizeof(abk_uvc_camera.usb_state), "released");
	abk_uvc_set_locked(abk_uvc_camera.fido_state,
			   sizeof(abk_uvc_camera.fido_state),
			   has_fido ? "present" : "not_present");
	abk_uvc_trace_locked("config_released");
	mutex_unlock(&abk_uvc_camera.lock);
}
EXPORT_SYMBOL_GPL(abk_uvc_camera_release_config);

static int __init abk_uvc_camera_init(void)
{
	int ret;

	mutex_init(&abk_uvc_camera.lock);
	abk_uvc_camera.enabled = false;
	strscpy(abk_uvc_camera.mode, "manual", sizeof(abk_uvc_camera.mode));
	strscpy(abk_uvc_camera.camera, "back", sizeof(abk_uvc_camera.camera));
	strscpy(abk_uvc_camera.profile, "mjpeg_1280x720", sizeof(abk_uvc_camera.profile));
	strscpy(abk_uvc_camera.backend, "none", sizeof(abk_uvc_camera.backend));
	strscpy(abk_uvc_camera.stream_state, "stopped", sizeof(abk_uvc_camera.stream_state));
	strscpy(abk_uvc_camera.usb_state, "unknown", sizeof(abk_uvc_camera.usb_state));
	strscpy(abk_uvc_camera.fido_state, "unknown", sizeof(abk_uvc_camera.fido_state));
	strscpy(abk_uvc_camera.last_trace, "initialized", sizeof(abk_uvc_camera.last_trace));

	abk_uvc_camera.kobj = kobject_create_and_add("abk_uvc_camera", kernel_kobj);
	if (!abk_uvc_camera.kobj)
		return -ENOMEM;
	ret = sysfs_create_group(abk_uvc_camera.kobj, &abk_uvc_attr_group);
	if (ret) {
		kobject_put(abk_uvc_camera.kobj);
		abk_uvc_camera.kobj = NULL;
		return ret;
	}
	pr_info("abk_uvc_camera: initialized (5.15-safe userspace UVC bridge)\n");
	return 0;
}

static void __exit abk_uvc_camera_exit(void)
{
	if (abk_uvc_camera.kobj) {
		sysfs_remove_group(abk_uvc_camera.kobj, &abk_uvc_attr_group);
		kobject_put(abk_uvc_camera.kobj);
		abk_uvc_camera.kobj = NULL;
	}
}

module_init(abk_uvc_camera_init);
module_exit(abk_uvc_camera_exit);

MODULE_DESCRIPTION("ABK Android USB UVC camera coordination state and configfs hook");
MODULE_LICENSE("GPL");
