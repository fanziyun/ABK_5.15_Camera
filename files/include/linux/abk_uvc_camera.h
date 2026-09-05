/* SPDX-License-Identifier: GPL-2.0 */
#ifndef _LINUX_ABK_UVC_CAMERA_H
#define _LINUX_ABK_UVC_CAMERA_H

#include <linux/list.h>
#include <linux/usb/composite.h>

/*
 * The configfs hook is intentionally observational.  A vendor Android tree
 * normally owns a configured uvc.0 instance; userspace links that instance so
 * its descriptor tree remains valid.  The hook must not create a blank UVC
 * instance and accidentally break FIDO/ADB enumeration.
 */
#if IS_ENABLED(CONFIG_ABK_UVC_CAMERA)
int abk_uvc_camera_prepare_config(struct usb_composite_dev *cdev,
                                  struct usb_configuration *cfg,
                                  struct list_head *func_list);
void abk_uvc_camera_release_config(struct list_head *func_list);
#else
static inline int abk_uvc_camera_prepare_config(struct usb_composite_dev *cdev,
                                                struct usb_configuration *cfg,
                                                struct list_head *func_list)
{
    (void)cdev;
    (void)cfg;
    (void)func_list;
    return 0;
}

static inline void abk_uvc_camera_release_config(struct list_head *func_list)
{
    (void)func_list;
}
#endif

#endif /* _LINUX_ABK_UVC_CAMERA_H */
