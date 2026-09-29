package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.UiGitHubAuthState
import `in`.jphe.storyvox.feature.auth.AuthViewModel
import `in`.jphe.storyvox.feature.settings.components.StatusPill
import `in`.jphe.storyvox.feature.settings.components.StatusTone
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → Account subscreen (follow-up to #440 / #467).
 *
 * Fiction-source accounts: Royal Road (WebView cookie auth, #91), AO3
 * (#1592) and GitHub (Device Flow OAuth + scope toggle, #91 / #203).
 * #1821 removed the Cloud Sync section: Candela is local-only.
 */
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    onOpenRoyalRoadSignIn: () -> Unit,
    onOpenAo3SignIn: () -> Unit,
    onOpenGitHubSignIn: () -> Unit,
    onOpenGitHubRevoke: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ao3SignedIn by authViewModel.ao3SignedIn.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = stringResource(R.string.settings_account_title), onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            // ── Fiction Source Accounts ──────────────────────────────
            SettingsGroupCard {
                StatusPill(
                    text = if (s.isSignedIn) stringResource(R.string.settings_account_royal_road_signed_in) else stringResource(R.string.settings_account_royal_road_not_signed_in),
                    tone = if (s.isSignedIn) StatusTone.Connected else StatusTone.Neutral,
                )
                if (s.isSignedIn) {
                    SettingsRow(
                        title = stringResource(R.string.settings_account_royal_road_title),
                        subtitle = stringResource(R.string.settings_account_royal_road_signed_in_subtitle),
                        trailing = {
                            BrassButton(
                                label = stringResource(R.string.settings_sign_out),
                                onClick = viewModel::signOut,
                                variant = BrassButtonVariant.Secondary,
                            )
                        },
                    )
                    RoyalRoadTagSyncRow()
                } else {
                    SettingsRow(
                        title = stringResource(R.string.settings_account_royal_road_title),
                        subtitle = stringResource(R.string.settings_account_royal_road_signin_subtitle),
                        trailing = {
                            BrassButton(
                                label = stringResource(R.string.settings_sign_in),
                                onClick = onOpenRoyalRoadSignIn,
                                variant = BrassButtonVariant.Primary,
                            )
                        },
                    )
                }

                // #1592 — AO3 (web-session cookie auth). State observed off
                // AuthRepository (the same source Browse reads); sign-in via
                // the generic auth WebView; sign-out clears the cookie header
                // + the live OkHttp jar.
                StatusPill(
                    text = if (ao3SignedIn) stringResource(R.string.settings_account_ao3_signed_in) else stringResource(R.string.settings_account_ao3_not_signed_in),
                    tone = if (ao3SignedIn) StatusTone.Connected else StatusTone.Neutral,
                )
                SettingsRow(
                    title = stringResource(R.string.settings_account_ao3_title),
                    subtitle = stringResource(
                        if (ao3SignedIn) R.string.settings_account_ao3_signed_in_subtitle
                        else R.string.settings_account_ao3_signin_subtitle,
                    ),
                    trailing = {
                        if (ao3SignedIn) {
                            BrassButton(
                                label = stringResource(R.string.settings_sign_out),
                                onClick = { authViewModel.signOut(SourceIds.AO3) },
                                variant = BrassButtonVariant.Secondary,
                            )
                        } else {
                            BrassButton(
                                label = stringResource(R.string.settings_sign_in),
                                onClick = onOpenAo3SignIn,
                                variant = BrassButtonVariant.Primary,
                            )
                        }
                    },
                )

                StatusPill(
                    text = when (val g = s.github) {
                        UiGitHubAuthState.Anonymous -> stringResource(R.string.settings_account_github_not_signed_in)
                        is UiGitHubAuthState.SignedIn ->
                            g.login?.let { stringResource(R.string.settings_account_github_signed_in_as, it) }
                                ?: stringResource(R.string.settings_account_github_signed_in)
                        UiGitHubAuthState.Expired -> stringResource(R.string.settings_account_github_expired)
                    },
                    tone = when (s.github) {
                        UiGitHubAuthState.Anonymous -> StatusTone.Neutral
                        is UiGitHubAuthState.SignedIn -> StatusTone.Connected
                        UiGitHubAuthState.Expired -> StatusTone.Error
                    },
                )
                GitHubSignInRow(
                    state = s.github,
                    privateReposEnabled = s.githubPrivateReposEnabled,
                    onSignIn = onOpenGitHubSignIn,
                    onSignOut = viewModel::signOutGitHub,
                    onOpenRevokePage = onOpenGitHubRevoke,
                    onSetPrivateReposEnabled = viewModel::setGitHubPrivateReposEnabled,
                )
            }
        }
    }
}
