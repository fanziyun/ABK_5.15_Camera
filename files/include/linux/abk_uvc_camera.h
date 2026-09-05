/* SPDX-License-Identifier: GPL-2.0 */
#ifndef _LINUX_ABK_UVC_CAMERA_H
#define _LINUX_ABK_UVC_CAMERA_H

#include <linux/list.h>
#include <linux/usb/composite.h>

/*
 * The configfs hook observes the function list and, when the camera is
 * enabled, injects the vendor-preconfigured uvc.0 function into the active
 * config before it is bound.  The injected function is removed again during
 * configfs purge so the FIDO/ADB lifecycle stays untouched.
 */
#if IS_ENABLED(CONFIG_ABK_UVC_CAMERA)
int abk_uvc_camera_prepare_config(struct usb_composite_dev *cdev,
                                  struct usb_configuration *cfg,
                                  struct list_head *func_list,
                                  struct list_head *available_func);
void abk_uvc_camera_drop_injected(struct list_head *func_list);
void abk_uvc_camera_release_config(struct list_head *func_list);
#else
static inline int abk_uvc_camera_prepare_config(struct usb_composite_dev *cdev,
                                                struct usb_configuration *cfg,
                                                struct list_head *func_list,
                                                struct list_head *available_func)
{
    (void)cdev;
    (void)cfg;
    (void)func_list;
    (void)available_func;
    return 0;
}

static inline void abk_uvc_camera_drop_injected(struct list_head *func_list)
{
    (void)func_list;
}

static inline void abk_uvc_camera_release_config(struct list_head *func_list)
{
    (void)func_list;
}
#endif

#endif /* _LINUX_ABK_UVC_CAMERA_H */
