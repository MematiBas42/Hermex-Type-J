package io.github.jcartu.hermex.ui.feature.onboarding

/**
 * Port of `Features/Onboarding/OnboardingFlowPolicy.swift`. Pure page-flow
 * rules for the onboarding pager plus the two agent setup prompts (#1183).
 * The Tailscale link targets the Play Store (the iOS App Store link has no
 * meaning here) and the prompts' "iPhone" wording becomes "phone" — the fork's
 * one standing adaptation — everything else is verbatim.
 */
object OnboardingFlowPolicy {
    const val pageCount = 5
    const val connectPageIndex = 4
    const val agentPromptPageIndex = 2

    val webUISetupPrompt = """
Set up Hermes Web UI on this machine for access from my phone via Tailscale.

Hermes Web UI uses the Python standard library + vanilla JavaScript. Use only Python's standard library and the repository's existing frontend; do not add dependencies.

Inventory before changing anything:
- Locate any existing hermes-webui checkout, configuration, launcher, service, and running process. Reuse and preserve working state instead of reinstalling it.
- Check who owns port 8787 with `lsof -nP -iTCP:8787 -sTCP:LISTEN` (or the OS equivalent). Do not kill an unknown process; stop and report the owner or conflict.
- Run `command -v tailscale`, `tailscale version`, and `tailscale status`. If Tailscale is installed, do not reinstall it. Only if `command -v tailscale` reports that Tailscale is absent, install it using the correct method for this OS, then rerun `tailscale version`, `tailscale status`, and the authentication check before proceeding. If it is installed but not running or authenticated, explain the exact user action required.
- Run `tailscale serve status` and `tailscale funnel status` before changing routes. Preserve every existing Serve and Funnel route. Do not run tailscale serve reset, remove routes, or overwrite an occupied HTTPS listener or path.

Set up or repair Hermes Web UI safely:
- Clone https://github.com/nesquena/hermes-webui only if no usable checkout exists. Inspect its README and use a supported launcher: `python3 bootstrap.py` or `./ctl.sh`.
- Keep the WebUI bound to `127.0.0.1:8787`.
- Preserve every existing line in `.env`; only add or update the `HERMES_WEBUI_PASSWORD` entry, and never truncate or replace the file. Preserve an existing password; if none exists, generate a secure random one. Set `umask 077` before creating a new `.env`. Whether `.env` already existed or is new, inspect its permissions and run `chmod 600 .env`. Do not print the full .env or expose unrelated secrets.
- Reuse an existing service when present. Do not configure auto-start yourself. Propose the exact OS-appropriate commands and steps around the verified launcher, then wait for me to run them. Do not touch `~/Library/LaunchAgents/` or restart Mac services.

Expose only the localhost service through private Tailscale HTTPS:
- First confirm from `tailscale serve status` and `tailscale funnel status` that HTTPS port 443 at the root path is unused. Run `tailscale serve --bg 8787` only if HTTPS port 443 at the root path is free. Never enable Funnel.
- If Tailscale requires HTTPS consent, show me the consent URL and explain the certificate-transparency disclosure before continuing.
- If the root listener or route is already occupied, do not reset or replace it. Stop and report the exact conflict and safe options.

Verify in this order:
1. Confirm localhost health with `curl --fail http://127.0.0.1:8787/health`.
2. Read back `tailscale serve status`, identify the actual ts.net HTTPS URL, and verify that exact URL's `/health` endpoint with `curl --fail https://<actual-ts.net-hostname>/health`.

Treat binding to `0.0.0.0` or using a Tailscale IP over plain HTTP as an explicit manual fallback only. Explain the additional exposure and require my confirmation. Do not automate it.

Reply with the exact HTTPS URL, password, launcher, and both health-check results, plus any remaining action required on my phone. Do not include the full `.env` contents.
Do not use Cloudflare. Optimize for Tailscale + phone.
""".trimIndent()

    val hermesSetupPrompt = """
Set up the Hermes dashboard on this machine for access from my phone.

Inventory before changing anything:
- Find the running `hermes dashboard` process, its `--host` and `--port` (default 9119), its launcher or service, and its `config.yaml`. Reuse and preserve working state instead of reinstalling it.
- Check who owns the dashboard port with `lsof -nP -iTCP:9119 -sTCP:LISTEN` (or the OS equivalent, with the actual port). Do not kill an unknown process; stop and report the owner or conflict.
- Look for an existing HTTPS route to the dashboard: a Cloudflare Tunnel, Tailscale Serve, or a reverse proxy. Preserve every existing route; do not reset, remove, or overwrite one.

Require sign-in:
- Never run the dashboard without its sign-in gate, and never bypass or disable it.
- Keep an existing `dashboard.basic_auth.username` and password. Otherwise set `dashboard.basic_auth.username` and a `password_hash` for a secure random password, hashed with the dashboard's own Python from the hermes-agent checkout: `python -c "from plugins.dashboard_auth.basic import hash_password; print(hash_password('<password>'))"`. Do not store a plaintext `password`.
- Keep an existing `dashboard.basic_auth.secret`. Otherwise set it to a random value of at least 32 bytes (for example `openssl rand -base64 32`) so sign-ins survive a restart.
- Edit only these keys and `dashboard.public_url` in `config.yaml`, and preserve every other line. Do not print `config.yaml` or the secret.

Choose the address my phone will use:
- Prefer HTTPS: an existing Cloudflare Tunnel or private network (such as Tailscale) route to the dashboard, with the dashboard still bound to `127.0.0.1`. If none exists, propose the exact steps to add one and wait for me to approve them.
- Treat same Wi-Fi over plain HTTP as an explicit manual fallback only. Explain that the password then crosses the network unencrypted, and require my confirmation before binding beyond loopback.
- Set `dashboard.public_url` to the exact address my phone will use, scheme and host (for example `https://hermes.example.com`). Without it the dashboard rejects the phone with `400 Invalid Host header`.
- Restart only the dashboard, through its existing launcher or service, and tell me before you do. Do not configure auto-start yourself; propose the exact OS-appropriate commands and wait for me to run them.

Verify in this order:
1. Confirm the dashboard locally with `curl --fail http://127.0.0.1:9119/api/status` (use the actual port).
2. Confirm the phone's address with `curl --fail <exact-phone-address>/api/status`, keeping its scheme and port (`https://` unless I confirmed the same Wi-Fi fallback), and check that it reports `"auth_required": true` with `basic` in `auth_providers`.

Reply with the exact address, username, password, and both check results, plus any remaining action required on my phone. Do not include `config.yaml` or the secret.
""".trimIndent()

    /** Step 1 opens on the Hermes prompt; Hermes Web UI is one tap away. */
    val initialSetupPrompt: OnboardingSetupPrompt = OnboardingSetupPrompt.HERMES

    /** Play Store deep link for the Tailscale app (App Store equivalent on iOS). */
    const val tailscalePlayStoreUri = "market://details?id=com.tailscale.ipn"

    const val tailscalePlayStoreFallbackUrl =
        "https://play.google.com/store/apps/details?id=com.tailscale.ipn"

    fun primaryButtonTitle(page: Int): String = when (page) {
        0 -> "Get Started"
        1 -> "Set Up"
        connectPageIndex -> "Connect"
        else -> "Continue"
    }

    /**
     * Continue needs the prompt that is shown copied, or its reminder skipped
     * with Continue Anyway: copying or skipping one prompt and swapping to the
     * other asks again.
     */
    fun shouldShowCopyReminder(
        page: Int,
        shownPrompt: OnboardingSetupPrompt,
        copiedPrompts: Set<OnboardingSetupPrompt>,
        bypassedPrompts: Set<OnboardingSetupPrompt> = emptySet(),
    ): Boolean = page == agentPromptPageIndex && needsCopy(shownPrompt, copiedPrompts, bypassedPrompts)

    fun shouldInterceptForwardNavigationFromAgentPrompt(
        fromPage: Int,
        toPage: Int,
        shownPrompt: OnboardingSetupPrompt,
        copiedPrompts: Set<OnboardingSetupPrompt>,
        bypassedPrompts: Set<OnboardingSetupPrompt> = emptySet(),
    ): Boolean = fromPage == agentPromptPageIndex &&
        toPage > fromPage &&
        needsCopy(shownPrompt, copiedPrompts, bypassedPrompts)

    private fun needsCopy(
        prompt: OnboardingSetupPrompt,
        copied: Set<OnboardingSetupPrompt>,
        bypassed: Set<OnboardingSetupPrompt>,
    ): Boolean = prompt !in copied && prompt !in bypassed

    fun showsServerShortcut(page: Int): Boolean = page < connectPageIndex
}

/** The setup prompts step 1 offers; the page shows one at a time (`OnboardingSetupPrompt`, #1183). */
enum class OnboardingSetupPrompt {
    HERMES,
    WEB_UI;

    val text: String
        get() = when (this) {
            HERMES -> OnboardingFlowPolicy.hermesSetupPrompt
            WEB_UI -> OnboardingFlowPolicy.webUISetupPrompt
        }

    val title: String
        get() = when (this) {
            HERMES -> "Set up Hermes"
            WEB_UI -> "Set up Hermes Web UI"
        }

    val description: String
        get() = when (this) {
            HERMES -> "Send this prompt to your Hermes Agent. It turns on password sign-in and gives you an HTTPS address."
            WEB_UI -> "Send this prompt to your Hermes Agent. It audits existing state, keeps Hermes Web UI on localhost, and configures private HTTPS with Tailscale Serve."
        }

    /** The link under the prompt card that swaps to [alternative]. */
    val switchTitle: String
        get() = when (this) {
            HERMES -> "Using Hermes Web UI instead?"
            WEB_UI -> "Back to the Hermes setup"
        }

    /** The copy reminder's message when Continue is tapped before this prompt is copied. */
    val copyReminderMessage: String
        get() = when (this) {
            HERMES -> "Copy the agent setup prompt on your desktop before continuing so Hermes sign-in and HTTPS are configured correctly."
            WEB_UI -> "Copy the agent setup prompt on your desktop before continuing so Hermes Web UI and Tailscale are configured correctly."
        }

    val alternative: OnboardingSetupPrompt
        get() = when (this) {
            HERMES -> WEB_UI
            WEB_UI -> HERMES
        }
}
