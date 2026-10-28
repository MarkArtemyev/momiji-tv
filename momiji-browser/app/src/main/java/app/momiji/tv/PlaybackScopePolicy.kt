package app.momiji.tv

/** Pure decisions for restoring playback without moving a saved position between profiles. */
object PlaybackScopePolicy {
    const val PENDING = "pending"
    const val DISABLED = "disabled"
    enum class HomeState { ABSENT, AVAILABLE, UNAVAILABLE }
    enum class RequestKind { FRESH, CAPTURED, STANDALONE, LEGACY, DISABLED }
    data class Snapshot(val scope: String?, val profile: String?, val page: String?, val position: Long)
    data class Request(val kind: RequestKind, val profile: String? = null)
    data class Decision(val scope: String, val useSession: Boolean, val restorePosition: Boolean) {
        val localKey: String? get() = scope.takeUnless { it == DISABLED || it == PENDING }
        val standalone: Boolean get() = scope.startsWith("standalone|")
    }

    fun profileScope(profile: String, page: String) = "profile|$profile|$page"
    fun standaloneScope(source: String) = "standalone|$source"

    fun request(saved: Snapshot?, currentPage: String?): Request {
        if (saved == null) return Request(RequestKind.FRESH)
        if (saved.scope == null) return Request(RequestKind.LEGACY)
        if (saved.scope.startsWith("standalone|")) return Request(RequestKind.STANDALONE)
        val profile = saved.profile
        if (profile != null && profile.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
            saved.page != null && saved.page == currentPage && saved.scope == profileScope(profile, saved.page))
            return Request(RequestKind.CAPTURED, profile)
        // Pending capture, a failed previous capture, or inconsistent saved state cannot capture
        // whichever profile happens to be active after the activity has been recreated.
        return Request(RequestKind.DISABLED)
    }

    fun decide(request: Request, saved: Snapshot?, source: String, page: String?, home: HomeState,
        sessionProfile: String?, sessionPage: String?): Decision {
        if (home == HomeState.AVAILABLE && sessionProfile != null && sessionPage == page && page != null &&
            (request.kind == RequestKind.FRESH || request.kind == RequestKind.CAPTURED && request.profile == sessionProfile)) {
            val scope = profileScope(sessionProfile, page)
            return Decision(scope, true, saved != null && saved.scope == scope && saved.profile == sessionProfile && saved.page == page)
        }
        if (home == HomeState.ABSENT && request.kind in setOf(RequestKind.FRESH, RequestKind.STANDALONE, RequestKind.LEGACY)) {
            val scope = standaloneScope(source)
            return Decision(scope, false, saved?.scope == scope || request.kind == RequestKind.LEGACY)
        }
        // An installed but inaccessible Home never falls back to a shared standalone history.
        // A captured profile also stays bound when Home disappears or that profile is deleted.
        return Decision(DISABLED, false, false)
    }
}
