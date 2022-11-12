package io.beatmaps.api

import io.beatmaps.cdnPrefix
import io.beatmaps.common.ReviewDeleteData
import io.beatmaps.common.ReviewModerationData
import io.beatmaps.common.UploadLimitData
import io.beatmaps.common.db.NowExpression
import io.beatmaps.common.db.upsert
import io.beatmaps.common.dbo.Beatmap
import io.beatmaps.common.dbo.BeatmapDao
import io.beatmaps.common.dbo.ModLog
import io.beatmaps.common.dbo.Review
import io.beatmaps.common.dbo.ReviewDao
import io.beatmaps.common.dbo.User
import io.beatmaps.common.dbo.UserDao
import io.beatmaps.common.dbo.curatorAlias
import io.beatmaps.common.dbo.joinCurator
import io.beatmaps.common.dbo.joinUploader
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.locations.Location
import io.ktor.server.locations.delete
import io.ktor.server.locations.get
import io.ktor.server.locations.post
import io.ktor.server.locations.put
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.Index
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun ReviewDetail.Companion.from(other: ReviewDao, cdnPrefix: String, beatmap: Boolean) =
    ReviewDetail(
        other.id.value,
        if (beatmap) null else UserDetail.from(other.user),
        if (beatmap) MapDetail.from(other.map, cdnPrefix) else null,
        other.text,
        ReviewSentiment.fromInt(other.sentiment),
        other.createdAt.toKotlinInstant(), other.updatedAt.toKotlinInstant(), other.curatedAt?.toKotlinInstant(), other.deletedAt?.toKotlinInstant()
    )

fun ReviewDetail.Companion.from(row: ResultRow, cdnPrefix: String) = from(ReviewDao.wrapRow(row), cdnPrefix, row.hasValue(Beatmap.id))

@Location("/api/review")
class ReviewApi {
    @Location("/map/{id}/{page?}")
    data class ByMap(val id: String, val page: Long = 0, val api: ReviewApi)

    @Location("/user/{id}/{page?}")
    data class ByUser(val id: Int, val page: Long = 0, val api: ReviewApi)

    @Location("/single/{mapId}/{userId}")
    data class Single(val mapId: String, val userId: Int, val api: ReviewApi)

    @Location("/curate")
    data class Curate(val api: ReviewApi)
}

fun Route.reviewRoute() {
    if (!ReviewConstants.COMMENTS_ENABLED) return

    get<ReviewApi.ByMap> {
        val reviews = transaction {
            try {
                Review
                    .join(User, JoinType.INNER, Review.userId, User.id)
                    .select {
                        Review.mapId eq it.id.toInt(16) and Review.deletedAt.isNull()
                    }
                    .orderBy(
                        Review.curatedAt to SortOrder.DESC_NULLS_LAST,
                        Review.createdAt to SortOrder.DESC
                    )
                    .limit(it.page)
                    .map {
                        UserDao.wrapRow(it)
                        ReviewDetail.from(it, cdnPrefix())
                    }
            } catch (_: NumberFormatException) {
                null
            }
        }

        if (reviews == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(ReviewsResponse(reviews))
        }
    }

    get<ReviewApi.ByUser> {
        val reviews = transaction {
            try {
                Review
                    .join(Beatmap, JoinType.INNER, Review.mapId, Beatmap.id)
                    .joinUploader()
                    .joinCurator()
                    .select {
                        Review.userId eq it.id and Review.deletedAt.isNull()
                    }
                    .orderBy(
                        Review.createdAt, SortOrder.DESC
                    )
                    .limit(it.page)
                    .map { row ->
                        if (row.hasValue(User.id)) {
                            UserDao.wrapRow(row)
                        }
                        if (row.hasValue(curatorAlias[User.id]) && row[Beatmap.curator] != null) {
                            UserDao.wrapRow(row, curatorAlias)
                        }

                        BeatmapDao.wrapRow(row)
                        ReviewDetail.from(row, cdnPrefix())
                    }
            } catch (_: NumberFormatException) {
                null
            }
        }

        if (reviews == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(ReviewsResponse(reviews))
        }
    }

    get<ReviewApi.Single> {
        val review = transaction {
            try {
                Review
                    .join(User, JoinType.INNER, Review.userId, User.id)
                    .select {
                        Review.mapId eq it.mapId.toInt(16) and (Review.userId eq it.userId) and Review.deletedAt.isNull()
                    }
                    .singleOrNull()
                    ?.let { row ->
                        UserDao.wrapRow(row)
                        ReviewDetail.from(row, cdnPrefix())
                    }
            } catch (_: NumberFormatException) {
                null
            }
        }

        if (review == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(review)
        }
    }

    put<ReviewApi.Single> { single ->
        requireAuthorization { sess ->
            val update = call.receive<PutReview>()
            val updateMapId = single.mapId.toInt(16)
            val newText = update.text.take(ReviewConstants.MAX_LENGTH)

            if (single.userId != sess.userId && !sess.isCurator()) {
                call.respond(HttpStatusCode.Forbidden)
                return@requireAuthorization
            }

            captchaIfPresent(update.captcha) {
                transaction {
                    val oldData = if (single.userId != sess.userId) {
                        ReviewDao.wrapRow(Review.select { Review.mapId eq updateMapId and (Review.userId eq single.userId) and Review.deletedAt.isNull() }.single())
                    } else {
                        null
                    }

                    if (update.captcha == null) {
                        Review.update({ Review.mapId eq updateMapId and (Review.userId eq single.userId) and Review.deletedAt.isNull() }) { r ->
                            r[updatedAt] = NowExpression(updatedAt.columnType)
                            r[text] = newText
                            r[sentiment] = update.sentiment.dbValue
                        }
                    } else {
                        Review.upsert(conflictIndex = Index(listOf(Review.mapId, Review.userId), true, "review_unique")) { r ->
                            r[mapId] = updateMapId
                            r[userId] = single.userId
                            r[text] = newText
                            r[sentiment] = update.sentiment.dbValue
                            r[createdAt] = NowExpression(createdAt.columnType)
                            r[updatedAt] = NowExpression(updatedAt.columnType)
                            r[deletedAt] = null
                        }
                    }

                    if (single.userId != sess.userId && oldData != null) {
                        ModLog.insert(
                            sess.userId,
                            updateMapId,
                            ReviewModerationData(oldData.sentiment, update.sentiment.dbValue, oldData.text, newText),
                            single.userId
                        )
                    }
                }

                call.respond(HttpStatusCode.OK)
            }
        }
    }

    delete<ReviewApi.Single> { single ->
        requireAuthorization { sess ->
            val deleteReview = call.receive<DeleteReview>()
            val mapId = single.mapId.toInt(16)

            if (single.userId != sess.userId && !sess.isCurator()) {
                call.respond(HttpStatusCode.Forbidden)
                return@requireAuthorization
            }

            transaction {
                val result = Review.update({ Review.mapId eq mapId and (Review.userId eq single.userId) and Review.deletedAt.isNull() }) { r ->
                    r[deletedAt] = NowExpression(deletedAt.columnType)
                } > 0

                if (result && single.userId != sess.userId) {
                    ModLog.insert(
                        sess.userId,
                        mapId,
                        ReviewDeleteData(deleteReview.reason),
                        single.userId
                    )
                }
            }

            call.respond(HttpStatusCode.OK)
        }
    }

    post<ReviewApi.Curate> {
        requireAuthorization { user ->
            if (!user.isCurator()) {
                call.respond(HttpStatusCode.BadRequest)
            } else {
                val reviewUpdate = call.receive<CurateReview>()

                transaction {
                    fun curateReview() =
                        Review.update({
                            (Review.id eq reviewUpdate.id) and (if (reviewUpdate.curated) Review.curatedAt.isNull() else Review.curatedAt.isNotNull()) and Review.deletedAt.isNull()
                        }) {
                            if (reviewUpdate.curated) {
                                it[curatedAt] = NowExpression(curatedAt.columnType)
                            } else {
                                it[curatedAt] = null
                            }
                        }

                    curateReview()
                }

                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
