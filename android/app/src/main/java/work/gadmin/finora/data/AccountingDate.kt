package work.gadmin.finora.data

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Receipt dates and due dates follow the organization's accounting day. */
val accountingZone: ZoneId = ZoneId.of("Europe/Chisinau")

fun accountingToday(clock: Clock = Clock.systemUTC()): LocalDate =
    LocalDate.now(clock.withZone(accountingZone))
