package ch.nokillswit.infra.db

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder

/**
 * The PostgreSQL containment operator on a `jsonb` expression: renders `<expression> @> CAST(? AS jsonb)`, true when the stored document
 * contains [document] (a JSON TEXT: an object whose every key holds an equal value or, for an array, a superset; numbers compare by
 * value, so a string never equals a number). Exposed ships no `@>` for the repo-local `jsonb` column ([JsonbColumnType]), so this is a
 * custom [Op]; the document is a bound argument, never concatenated into the statement.
 */
fun Expression<*>.jsonbContains(document: String): Op<Boolean> = JsonbContains(this, document)

private class JsonbContains(private val expression: Expression<*>, private val document: String) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append(expression)
        queryBuilder.append(" @> CAST(")
        queryBuilder.registerArgument(JsonbColumnType(), document)
        queryBuilder.append(" AS jsonb)")
    }
}
