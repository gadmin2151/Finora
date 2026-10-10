package work.gadmin.finora.data

import java.time.LocalDate

fun debtIsOverdue(
    debt: Debt,
    today: LocalDate = accountingToday(),
): Boolean =
    debt.remaining_minor > 0 &&
        runCatching { debt.due_date?.let(LocalDate::parse)?.isBefore(today) == true }
            .getOrDefault(false)

fun filterDebts(
    debts: List<Debt>,
    direction: String,
    includeClosed: Boolean,
    overdueOnly: Boolean,
    search: String,
    today: LocalDate = accountingToday(),
): List<Debt> = debts.filter {
    (direction == "all" || it.direction == direction) &&
        (includeClosed || it.remaining_minor > 0) &&
        (!overdueOnly || debtIsOverdue(it, today)) &&
        (it.person.contains(search, true) || it.note.contains(search, true))
}
