package com.klk.hams.data.location

enum class GpsLockState {
    NoPermission,
    Acquiring,
    Locked,
    Stale,
    LocationDisabled
}
