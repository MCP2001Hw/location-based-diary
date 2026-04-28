# Location-Based Diary

Final year dissertation project — Heriot-Watt University BSc Computer Science (2025–26).  
Supervised by Dr. Phil Bartie.

A full-stack Android application that triggers task reminders when the user is physically near a relevant place at the right time. Rather than reminding you at a fixed time, the app reminds you *when and where it makes sense* — walk past a pharmacy and get reminded to pick up your prescription.

---

## Structure

```
location-based-diary/
├── android/        Kotlin Android app (Jetpack Compose, Fused Location Provider)
└── backend/        Python Flask REST API (PostgreSQL/PostGIS, deployed on Render)
```

---

## How It Works

1. A background **Foreground Service** polls the device location continuously using the Fused Location Provider API.
2. On each location update, a **local temporal filter** checks whether any tasks are scheduled for the current time and day — eliminating unnecessary network calls.
3. Matching tasks are sent to the **Flask backend**, which runs an `ST_DWithin` PostGIS spatial query against Edinburgh OpenStreetMap data to find nearby points of interest.
4. If the user is within range of a relevant place, a **notification fires**.
5. A **dwelling detection** system suppresses repeated notifications within the same geofence so the user isn't spammed.

---

## Android (`/android`)

**Stack:** Kotlin, Jetpack Compose, Fused Location Provider, Activity Recognition API, Coroutines

**Key components:**

| File | Role |
|---|---|
| `LocationService.kt` | Foreground service — continuous GPS polling, activity-aware suppression, geofence logic |
| `ApiClient.kt` | HTTP layer — all backend communication, one request per function |
| `TaskFilter.kt` | Local temporal validation — filters tasks by time/weekday before hitting the network |
| `NotificationHelper.kt` | Notification creation and channel management |
| `LeafletMapScreen.kt` | WebView-based map using Leaflet.js for task/friend visualisation |
| `LoginScreen.kt` | Auth UI — login and registration |
| `DashboardDialogs.kt` | Task management UI components |

**Notable design decisions:**
- Activity recognition suppresses GPS polling when the user is stationary, significantly reducing battery drain.
- All network calls are isolated in `ApiClient` with no Android-specific dependencies, making them easily testable.
- `AppPreferences` centralises all SharedPreferences access.

---

## Backend (`/backend`)

**Stack:** Python, Flask, PostgreSQL/PostGIS, NeonDB, deployed on Render

**API endpoints:** 16 REST endpoints across authentication, task CRUD, spatial geofencing, breadcrumb trail logging, and Social Radar.

| Category | Endpoints |
|---|---|
| Auth | `/login`, `/register` |
| Tasks | `/tasks`, `/tasks/<id>`, `/tasks/check-location` |
| Friends & Social Radar | `/friends`, `/friend-requests`, `/friend-location` |
| Breadcrumbs | `/breadcrumbs` |
| User | `/user`, `/user/update` |

**Notable design decisions:**
- `ST_DWithin` with a PostGIS spatial index keeps geofence queries fast even across the full Edinburgh OSM dataset.
- Salted password hashing via Werkzeug.
- Social Radar uses per-friendship privacy toggles — sharing is bilateral and opt-in per relationship.

---

## Evaluation

Validated through:
- Emulator simulation across multiple location scenarios
- Real-device testing on Xiaomi 14T Pro (Android 16)
- User study (n=3): mean overall usability **4.33/5**, battery satisfaction **4.33/5**

---

## Setup

### Backend
```bash
pip install -r requirements.txt
python app.py
```
Requires a PostgreSQL/PostGIS database with Edinburgh OSM data loaded via `osm2pgsql`.  
Set your `DATABASE_URL` environment variable before running.

### Android
Open the `/android` folder in Android Studio. Update `BASE_URL` in `ApiClient.kt` to point to your backend instance. Build and run on a device or emulator with Google Play Services.

---

## Notes

This was built as a solo final year dissertation. The backend and Android app were designed and implemented entirely by me. Feedback and questions welcome via GitHub Issues.
