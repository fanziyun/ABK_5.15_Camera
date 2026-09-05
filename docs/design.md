# ABK UVC camera design notes

## Boundary

- Kernel module never creates an unconfigured UVC descriptor tree.  The device
  tree owns `/config/usb_gadget/g1/functions/uvc.0`; userspace links it.
- Camera2 permission and frame capture remain in userspace.
- No arbitrary shell interface is exposed.
- FIDO ownership is preserved: the camera hook never removes the `abk_fido`
  function.

## ConfigFS hook ordering

Both FIDO and camera patch `configfs.c`.  Each installer uses its own marker
and the camera patch is intentionally observational, so the modules can be
installed in either order without double hooks or cross-release.

## Bandwidth decision

Raw YUY2 1080p60 (1.99 Gbps) and 4K60 (7.96 Gbps) exceed USB 2.0.  Stable
delivery stops at 720p YUY2/MJPEG plus 1080p30 MJPEG.  Higher rates require
compression and are marked experimental.  If 4K60 must become stable, USB 3.x
or a network/private receiver transport is required.
