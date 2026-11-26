# Yvora 1.1 Camera calibration

The default region is the full normalized camera frame, preserving the prior pointer mapping. The user can adjust width and height independently (30–100%) and shift the region center, with boundaries clamped to the actual frame. Settings persist locally and map the selected camera area to the full normalized control coordinate space before the existing adaptive filter. The live schematic displays the real hand centroid and analysis aspect ratio at up to 4 Hz, not a second camera feed. Safe calibration suppresses taps and swipes from tracking; turn it off and present an open hand to resume gestures. The manual gesture-test buttons remain explicit touch actions.

Optional distance detail requests 360×480 on low-RAM devices or 480×640 otherwise, applied on restart. Actual resolution is reported by the analyzer. It may improve small-hand detection but costs processing time and cannot enlarge the physical lens field of view or guarantee a distance. Automatic frame pacing remains in place.

Validation: unit tests cover default mapping, reduced range, clamping and offsets. CI and physical-device validation required. No measured distance improvement is claimed.
