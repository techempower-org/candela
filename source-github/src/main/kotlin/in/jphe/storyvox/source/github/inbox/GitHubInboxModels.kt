package `in`.jphe.storyvox.source.github.inbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Issue #1470 — wire models for the GitHub inbox narrator. Only the
 * fields the narrator reads are declared; GitHubJson ignores the rest.
 */

@Serializable
internal data class GhUser(
    @SerialName("login") val login: String = "",
    @SerialName("type") val type: String? = null,
)

/** `GET /notifications` item. */
@Serializable
internal data class GhNotification(
    @SerialName("id") val id: String,
    @SerialName("unread") val unread: Boolean = false,
    @SerialName("reason") val reason: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("subject") val subject: GhNotificationSubject,
    @SerialName("repository") val repository: GhNotificationRepo,
)

@Serializable
internal data class GhNotificationSubject(
    @SerialName("title") val title: String = "",
    /** API url of the thread, e.g. `https://api.github.com/repos/o/r/pulls/12`.
     *  Null for CheckSuite / some Discussion notifications. */
    @SerialName("url") val url: String? = null,
    /** `PullRequest`, `Issue`, `Release`, `CheckSuite`, `Discussion`, `Commit`, … */
    @SerialName("type") val type: String = "",
)

@Serializable
internal data class GhNotificationRepo(
    @SerialName("full_name") val fullName: String,
)

@Serializable
internal data class GhLabel(
    @SerialName("name") val name: String = "",
)

/** Marker object present on an issue payload iff the issue is a pull request. */
@Serializable
internal data class GhPullRef(
    @SerialName("url") val url: String? = null,
    @SerialName("merged_at") val mergedAt: String? = null,
)

/** `GET /repos/{o}/{r}/issues/{n}` and `/search/issues` item (issues AND PRs). */
@Serializable
internal data class GhIssue(
    @SerialName("number") val number: Int,
    @SerialName("title") val title: String = "",
    @SerialName("body") val body: String? = null,
    @SerialName("user") val user: GhUser? = null,
    @SerialName("state") val state: String = "open",
    @SerialName("html_url") val htmlUrl: String? = null,
    /** `https://api.github.com/repos/{owner}/{repo}` — the only repo pointer search items carry. */
    @SerialName("repository_url") val repositoryUrl: String? = null,
    @SerialName("comments") val comments: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("labels") val labels: List<GhLabel> = emptyList(),
    @SerialName("draft") val draft: Boolean? = null,
    @SerialName("pull_request") val pullRequest: GhPullRef? = null,
)

@Serializable
internal data class GhIssueSearchResponse(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("items") val items: List<GhIssue> = emptyList(),
)

@Serializable
internal data class GhBranchRef(
    @SerialName("ref") val ref: String = "",
    @SerialName("sha") val sha: String = "",
)

/** `GET /repos/{o}/{r}/pulls/{n}` — the PR-only fields the issue payload lacks. */
@Serializable
internal data class GhPull(
    @SerialName("number") val number: Int,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("merged") val merged: Boolean = false,
    @SerialName("additions") val additions: Int = 0,
    @SerialName("deletions") val deletions: Int = 0,
    @SerialName("changed_files") val changedFiles: Int = 0,
    @SerialName("review_comments") val reviewComments: Int = 0,
    @SerialName("head") val head: GhBranchRef = GhBranchRef(),
    @SerialName("base") val base: GhBranchRef = GhBranchRef(),
)

/** Issue comment (`/issues/{n}/comments`) or inline review comment (`/pulls/{n}/comments`). */
@Serializable
internal data class GhComment(
    @SerialName("user") val user: GhUser? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** Present only on inline review comments. */
    @SerialName("path") val path: String? = null,
)

/** `GET /repos/{o}/{r}/pulls/{n}/reviews` item. */
@Serializable
internal data class GhReview(
    @SerialName("user") val user: GhUser? = null,
    @SerialName("body") val body: String? = null,
    /** `APPROVED`, `CHANGES_REQUESTED`, `COMMENTED`, `DISMISSED`, `PENDING`. */
    @SerialName("state") val state: String = "",
    @SerialName("submitted_at") val submittedAt: String? = null,
)

@Serializable
internal data class GhCheckRun(
    @SerialName("name") val name: String = "",
    /** `queued`, `in_progress`, `completed`. */
    @SerialName("status") val status: String = "",
    /** `success`, `failure`, `neutral`, `cancelled`, `skipped`, `timed_out`, `action_required`, null. */
    @SerialName("conclusion") val conclusion: String? = null,
)

@Serializable
internal data class GhCheckRunsResponse(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("check_runs") val checkRuns: List<GhCheckRun> = emptyList(),
)
