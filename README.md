# Vardhaman Attendance

Admin-only Android app for logging employee hours and generating monthly payslips.

- **android/** – Kotlin + Jetpack Compose app
- **backend/** – Node API on Vercel (`https://vardhaman-attendance.vercel.app/api`)
- **Database** – Supabase (cricbid_v0 project), tables `att_employee`, `att_entry`, `att_advance`

## Install
Download the latest `.apk` from [Releases](../../releases), open it on the phone and allow "install unknown apps".

## Pay rules
- Hours/day = exit − entry − lunch
- Gross = Σ hours × hourly rate (rate saved on each entry)
- Net = Gross − advances given that month

## Backend env vars (Vercel)
`SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `ADMIN_PIN`
