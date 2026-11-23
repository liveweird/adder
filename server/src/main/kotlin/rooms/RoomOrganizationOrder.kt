package ch.nokillswit.rooms

import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.Query

internal const val PERSONAL_ORDER = "personalOrder"

/** Applies the caller's saved order before the page cap, using ordinary activity order for unplaced rooms. */
internal suspend fun Query.applyRoomPaging(req: PageRequest, columns: Map<String, Expression<*>>, userId: UInt): Query {
    if (req.sort.none { it.name == PERSONAL_ORDER }) return applyPaging(req, columns)

    val ids = personalRoomOrder(userId).map(UInt::toInt)
    val rank = CustomFunction(
        "array_position",
        IntegerColumnType(),
        QueryParameter(ids, ArrayColumnType<Int, List<Int>>(IntegerColumnType())),
        RoomService.Rooms.id,
    )
    val unranked = Case().When(rank.isNull(), intLiteral(1)).Else(intLiteral(0))
    val unrankedActivity = Case().When(rank.isNull(), RoomService.Rooms.lastActivityAt).Else(longLiteral(Long.MAX_VALUE))
    val ordering = req.sort.flatMap { field ->
        val direction = if (field.descending) SortOrder.DESC else SortOrder.ASC
        if (field.name == PERSONAL_ORDER) {
            listOf(unranked to SortOrder.ASC, rank to direction, unrankedActivity to SortOrder.DESC)
        } else {
            listOf(columns.getValue(field.name) to direction)
        }
    }
    return orderBy(*ordering.toTypedArray()).limit(req.pageSize).offset((req.page - 1L) * req.pageSize)
}
