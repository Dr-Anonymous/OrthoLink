# OrthoLink

OrthoLink is an Android application designed for clinic management. It streamlines patient communication, consultation tracking, and quick access to patient information during calls.

## Features

### 📞 Live Call Overlay (Caller ID)
- **Instant Patient Recognition:** Displays patient details (Name, Last Visit, Diagnosis, Procedure) in an overlay when a call is received from a registered patient.
- **Quick Actions:**
  - **WhatsApp**: Send pre-configured messages (Location, Clinic Timings) with a single tap.
  - **Add Note**: Quickly note down call details.
- **Calendar Integration**: Shows upcoming appointments for the caller.
- **Intelligent Handling**: Automatically hides for saved contacts to avoid clutter.

### 🧩 Pending Consultations Widget
- **Home Screen Widget**: Displays a list of pending consultations directly on your home screen.
- **Grouping**: Categorizes consultations by location (e.g., Clinic, Hospital A, Hospital B).
- **Quick Refresh**: Tap the widget to refresh data instantly.
- **Smart Loading**: Features a visual loading state with safety timeouts to prevent UI hangs.

### 🤖 WhatsApp Automation
- **Accessibility Service**: Uses Android's Accessibility Services to automate message sending without manual typing.
- **Smart Retry**: Automatically handles "Not on WhatsApp" or connection errors with fallback to SMS.

## Tech Stack

- **Language**: Kotlin
- **Platform**: Android (Min SDK 23, Target SDK 34)
- **Backend**: Supabase (PostgreSQL + REST API)
- **Networking**: Retrofit + OkHttp + Gson
- **UI**: XML Layouts + RemoteViews (Widgets) + WindowManager (Overlay)

## Setup Instructions

### Prerequisites
- Android Studio Iguana or later
- JDK 17
- A Supabase project with appropriate tables (`patients`, `consultations`, `calendar_events`).

### Configuration
1. **Clone the Repository:**
   ```bash
   git clone https://github.com/Dr-Anonymous/OrthoLink.git
   ```
2. **Configure API Keys:**
   Create a `local.properties` file in the project root if it doesn't exist. Add your Supabase Anon Key:
   ```properties
   sdk.dir=/path/to/android/sdk
   SUPABASE_KEY=your_supabase_anon_key_here
   ```
   *Note: This file is ignored by git to keep your keys secure.*

3. **Build the Project:**
   Open the project in Android Studio and sync Gradle. The `BuildConfig` class will be generated with your API key.

## Architecture

The app follows a service-based architecture:
- **`OverlayService`**: A foreground service that manages the floating window for Caller ID.
- **`WhatsAppAutomationService`**: An Accessibility Service that interacts with the WhatsApp UI.
- **`PendingConsultationsWidget`**: An AppWidgetProvider that manages home screen updates.
- **`SupabaseClient`**: A singleton network client for API interactions.

## Permissions

The app requires sensitive permissions to function:
- `READ_CALL_LOG` / `READ_PHONE_STATE`: To detect incoming calls.
- `SYSTEM_ALERT_WINDOW`: To draw the overlay over other apps.
- `BIND_ACCESSIBILITY_SERVICE`: For WhatsApp automation.
- `INTERNET`: For backend communication.

## Contributing

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

Distributed under the MIT License. See `LICENSE` for more information.