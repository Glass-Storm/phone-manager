package com.glassstorm.phonemanager.domain.dto

/** A device paired with this phone hub. Pure data; no behaviour. */
data class Device(
    val GoDeviceId: String,
    val GoDeviceName: String,
    val GoRole: String,
)
