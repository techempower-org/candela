package `in`.jphe.storyvox.source.github.inbox

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Issue #1470 — pure text builders for the GitHub inbox narrator. Every
 * function here is side-effect free so the listening copy is unit-tested
 * without HTTP. Output is Markdown handed to `MarkdownChapterRenderer`,
 * which produces both the reader HTML and the TTS plain text.
 */
internal object InboxNarration {

    /** Comments narrated per thread — "recent", not the whole history. */
    const val RECENT_COMMENTS: Int = 20

    /** Per-comment cap; bot reports (coverage tables, review digests) run to pages. */
    const val MAX_COMMENT_CHARS: Int = 1500

    /**
     * Stable id of an inbox thread: `owner/repo/pull/12`, `owner/repo/issues/12`
     * or (#1841) `owner/repo/releases/<id>`. [localId] is part of stored
     * library fiction ids, so the issue/PR shapes must never change.
     */
    data class ThreadRef(val owner: String, val repo: String, val kind: Kind, val number: Int) {
        /** Issue/PR constructor kept for existing callers. */
        constructor(owner: String, repo: String, isPull: Boolean, number: Int) :
            this(owner, repo, if (isPull) Kind.Pull else Kind.Issue, number)

        enum class Kind(val localSegment: String, val apiSegment: String) {
            Issue("issues", "issues"),
            Pull("pull", "pulls"),
            Release("releases", "releases"),
        }

        val isPull: Boolean get() = kind == Kind.Pull
        val repoFullName: String get() = "$owner/$repo"
        val localId: String get() = "$owner/$repo/${kind.localSegment}/$number"
    }

    private val LOCAL_ID = Regex("""^([^/\s]+)/([^/\s]+)/(pull|issues|releases)/(\d+)$""")
    private val API_URL = Regex("""/repos/([^/\s]+)/([^/\s]+)/(pulls|issues|releases)/(\d+)$""")
    private val REPO_URL = Regex("""/repos/([^/\s]+)/([^/\s]+)$""")

    fun parseLocalId(localId: String): ThreadRef? {
        val m = LOCAL_ID.matchEntire(localId.trim()) ?: return null
        val (owner, repo, seg, n) = m.destructured
        val kind = ThreadRef.Kind.entries.first { it.localSegment == seg }
        return ThreadRef(owner, repo, kind, n.toIntOrNull() ?: return null)
    }

    /** From a notification's `subject.url` (`…/repos/o/r/pulls/12`). */
    fun parseApiUrl(url: String?): ThreadRef? {
        val m = API_URL.find(url?.trim().orEmpty()) ?: return null
        val (owner, repo, seg, n) = m.destructured
        val kind = ThreadRef.Kind.entries.first { it.apiSegment == seg }
        return ThreadRef(owner, repo, kind, n.toIntOrNull() ?: return null)
    }

    /** From a search item's `repository_url` + number + pull marker. */
    fun refFor(issue: GhIssue): ThreadRef? {
        val m = REPO_URL.find(issue.repositoryUrl?.trim().orEmpty()) ?: return null
        val (owner, repo) = m.destructured
        return ThreadRef(owner, repo, issue.pullRequest != null, issue.number)
    }

    /** Human phrase for a notification `reason`. */
    fun reasonLabel(reason: String): String = when (reason) {
        "review_requested" -> "Review requested"
        "mention" -> "You were mentioned"
        "team_mention" -> "Your team was mentioned"
        "assign" -> "Assigned to you"
        "author" -> "Your thread"
        "comment" -> "New comment"
        "state_change" -> "State changed"
        "ci_activity" -> "CI activity"
        "subscribed" -> "Watching"
        "manual" -> "Subscribed"
        "security_alert" -> "Security alert"
        "invitation" -> "Invitation"
        else -> "Notification"
    }

    fun kindLabel(isPull: Boolean): String = if (isPull) "Pull request" else "Issue"

    fun kindLabel(kind: ThreadRef.Kind): String = when (kind) {
        ThreadRef.Kind.Pull -> "Pull request"
        ThreadRef.Kind.Issue -> "Issue"
        ThreadRef.Kind.Release -> "Release"
    }

    /** The one chapter of a release thread (#1841): name, tag and who
     *  published it, then the release notes as written. */
    fun releaseMarkdown(ref: ThreadRef, release: GhRelease): String = buildString {
        val title = release.name?.takeIf { it.isNotBlank() } ?: release.tagName
        append("# ").append(title).append("\n\n")
        append("Release ").append(release.tagName).append(" of ").append(ref.repoFullName)
        release.author?.login?.takeIf { it.isNotBlank() }?.let { append(", published by ").append(it) }
        release.publishedAt?.take(10)?.takeIf { it.length == 10 }?.let { append(" on ").append(it) }
        append(".\n\n")
        append(release.body?.takeIf { it.isNotBlank() } ?: "This release has no notes.")
    }

    /**
     * Strip what reads badly aloud: HTML comments (PR templates, bot
     * markers), raw HTML tags (`<details>`, `<img>`), and runs of blank
     * lines. Markdown itself is left for the renderer.
     */
    fun sanitize(body: String?): String =
        body.orEmpty()
            .replace(Regex("""<!--[\s\S]*?-->"""), "")
            .replace(Regex("""</?[A-Za-z][^>]*>"""), "")
            .replace("\r\n", "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

    fun truncate(text: String, max: Int = MAX_COMMENT_CHARS): String {
        if (text.length <= max) return text
        val cut = text.substring(0, max)
        val lastBreak = maxOf(cut.lastIndexOf("\n\n"), cut.lastIndexOf(". "))
        val trimmed = if (lastBreak > max / 2) cut.substring(0, lastBreak + 1) else cut
        return trimmed.trimEnd() + "\n\n_(Comment trimmed.)_"
    }

    /** Summary of a PR's check runs, or null when there are none. */
    fun checksSentence(runs: List<GhCheckRun>): String? {
        if (runs.isEmpty()) return null
        val running = runs.count { it.status != "completed" }
        val done = runs.filter { it.status == "completed" }
        val failedNames = done
            .filter { it.conclusion in FAILING_CONCLUSIONS }
            .map { it.name }
        val passed = done.count { it.conclusion == "success" }
        val parts = buildList {
            if (passed > 0) add("$passed passed")
            if (failedNames.isNotEmpty()) {
                val names = failedNames.take(3).joinToString(", ")
                val more = if (failedNames.size > 3) " and ${failedNames.size - 3} more" else ""
                add("${failedNames.size} failed ($names$more)")
            }
            if (running > 0) add("$running still running")
        }
        if (parts.isEmpty()) return "Checks: ${runs.size} finished with no failures."
        return "Checks: ${parts.joinToString(", ")}."
    }

    private val FAILING_CONCLUSIONS = setOf("failure", "timed_out", "cancelled", "action_required")

    fun stateSentence(issue: GhIssue, pull: GhPull?): String = when {
        pull?.merged == true -> "Merged."
        issue.state == "closed" -> "Closed."
        pull?.draft == true || issue.draft == true -> "Open, draft."
        else -> "Open."
    }

    /** Chapter 1 — title, who/where, state, branch + diff size, labels, CI, then the description. */
    fun overviewMarkdown(
        ref: ThreadRef,
        issue: GhIssue,
        pull: GhPull?,
        checks: List<GhCheckRun>?,
    ): String = buildString {
        append("**").append(issue.title.ifBlank { "Untitled" }.escapeMd()).append("**\n\n")
        append(kindLabel(ref.isPull)).append(' ').append(ref.number)
            .append(" in ").append(ref.repoFullName)
        issue.user?.login?.takeIf { it.isNotBlank() }?.let { append(", opened by ").append(it) }
        append(". ").append(stateSentence(issue, pull))
        if (pull != null) {
            if (pull.head.ref.isNotBlank() && pull.base.ref.isNotBlank()) {
                append(" Merging ").append(pull.head.ref.escapeMd())
                    .append(" into ").append(pull.base.ref.escapeMd()).append('.')
            }
            append(' ').append(plural(pull.changedFiles, "file")).append(" changed, ")
                .append(plural(pull.additions, "addition")).append(" and ")
                .append(plural(pull.deletions, "deletion")).append('.')
        }
        append("\n\n")
        val labels = issue.labels.map { it.name }.filter { it.isNotBlank() }
        if (labels.isNotEmpty()) append("Labels: ").append(labels.joinToString(", ").escapeMd()).append(".\n\n")
        checks?.let(::checksSentence)?.let { append(it).append("\n\n") }
        val body = sanitize(issue.body)
        if (body.isBlank()) append("No description provided.") else append(body)
        append('\n')
    }

    /** One narratable entry in the conversation chapter. */
    data class Entry(val at: String?, val markdown: String)

    fun commentEntry(c: GhComment): Entry? {
        val body = truncate(sanitize(c.body))
        if (body.isBlank()) return null
        val who = c.user?.login?.ifBlank { null } ?: "Someone"
        val lead = if (c.path.isNullOrBlank()) "$who commented" else "$who commented on ${c.path}"
        return Entry(c.createdAt, "**${lead.escapeMd()}${dateSuffix(c.createdAt)}:**\n\n$body")
    }

    fun reviewEntry(r: GhReview): Entry? {
        val body = truncate(sanitize(r.body))
        val who = r.user?.login?.ifBlank { null } ?: "Someone"
        val verb = when (r.state) {
            "APPROVED" -> "approved these changes"
            "CHANGES_REQUESTED" -> "requested changes"
            "DISMISSED" -> "left a review that was dismissed"
            "PENDING" -> return null
            else -> if (body.isBlank()) return null else "reviewed"
        }
        val head = "**${who.escapeMd()} $verb${dateSuffix(r.submittedAt)}"
        return Entry(r.submittedAt, if (body.isBlank()) "$head.**" else "$head:**\n\n$body")
    }

    /** Chapter 2 — the [RECENT_COMMENTS] newest entries, told oldest → newest. */
    fun conversationMarkdown(entries: List<Entry>, totalKnown: Int): String {
        if (entries.isEmpty()) return "No comments yet."
        val recent = entries.sortedBy { it.at.orEmpty() }.takeLast(RECENT_COMMENTS)
        val total = maxOf(totalKnown, entries.size)
        val header = when {
            total > recent.size -> "The ${recent.size} most recent of $total comments and reviews."
            recent.size == 1 -> "One comment or review."
            else -> "${recent.size} comments and reviews, oldest first."
        }
        return buildString {
            append(header).append("\n\n")
            recent.forEachIndexed { i, e ->
                if (i > 0) append("\n\n")
                append(e.markdown)
            }
            append('\n')
        }
    }

    private val DATE = DateTimeFormatter.ofPattern("MMMM d", Locale.US)

    private fun dateSuffix(iso: String?): String {
        val instant = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
        return " on " + DATE.format(instant.atZone(ZoneId.systemDefault()))
    }

    fun plural(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

    /** Keep `*`/`_` in branch names and logins from toggling emphasis. */
    private fun String.escapeMd(): String = replace("*", "\\*").replace("_", "\\_")
}
