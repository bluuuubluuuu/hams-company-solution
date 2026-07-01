package com.klk.hams.time

import java.time.Instant

object Clock {
    fun nowUtcIso(): String = Instant.now().toString()
}
