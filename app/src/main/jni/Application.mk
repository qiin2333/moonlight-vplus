# Application.mk for Moonlight

# Our minimum version is Android 5.0
APP_PLATFORM := android-21

# C++ STL required for bass energy analyzer (uses <chrono>, <algorithm>, <cmath>)
APP_STL := c++_static

# We support 16KB pages
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
