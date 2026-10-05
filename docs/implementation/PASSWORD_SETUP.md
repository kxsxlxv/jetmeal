# One-time password setup for the existing hosted account

The user explicitly selected email + password, without signup, OTP or SMTP. The selected hosted account is email-confirmed but initially had no password. Preserve its Auth ID and owned history.

Open `scripts/set_account_password.cmd` on this Windows computer. In the selected project's Supabase dashboard, open Settings → API Keys and copy a server secret key (`sb_secret_…`) into the tool's **secret key** field. Enter the desired app password into its **password** field and click **Установить пароль**. Then sign in to the cloud APK with the existing email and that password.

The person operating the tool enters both credentials privately. They must never be sent to chat, written into Android developer configuration, or bundled into an APK. The tool sends the password directly over HTTPS to the official Auth Admin `PUT /auth/v1/admin/users/{id}` endpoint, updates the existing account, keeps credentials only in process memory and clears fields on success. It never writes tokens or credentials to disk. The ignored `.verification/password-setup-context.json` contains only the selected URL, existing account ID and email, not credentials.

The helper was verified against a real local Supabase Auth service: an admin password update preserved the user ID, and the exact new password (including surrounding spaces) authenticated successfully. The user subsequently completed hosted password setup privately. Inspection confirms password presence without reading its value or hash; the physical phone's saved authenticated session successfully reads profile, targets, diary and catalogue.

References: [Auth Admin updateUserById](https://supabase.com/docs/reference/javascript/auth-admin-updateuserbyid), [API key types](https://supabase.com/docs/guides/getting-started/api-keys).
