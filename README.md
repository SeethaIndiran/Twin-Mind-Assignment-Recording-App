# 🎙️ Twin Mind Recording App

A powerful Android application that allows users to **record voice**, **transcribe it into text**, and **generate AI-based summaries** using OpenAI’s APIs.  
Built with **Jetpack Compose**, **MVVM**, **Dagger Hilt**, **Kotlin Coroutines**, and **Clean Architecture** — this app demonstrates scalable Android development with real-world use cases.

---

## 🚀 Features

- 🎧 **Voice Recording** – Record audio seamlessly with start, pause, and stop functionality.  
- 🗒️ **Transcription** – Converts recorded audio into text using **OpenAI Whisper API**.  
- 🧠 **AI-Powered Summary** – Generates a short, clear summary of the transcript using **OpenAI GPT model**.  
- 💾 **Local Storage** – Saves recordings and summaries in local Room Database.  
- 🔁 **Real-Time Updates** – Displays transcription and summary updates live.  
- 📱 **Modern UI** – Built entirely with **Jetpack Compose** for smooth, declarative UI.  
- 🧩 **Modular Clean Architecture** – Separation of concerns with clear layers for UI, domain, and data.  

---

## 🏗️ Tech Stack

| Layer | Technology |
|-------|-------------|
| **UI** | Jetpack Compose, Material 3 |
| **Architecture** | MVVM + Clean Architecture |
| **Dependency Injection** | Dagger Hilt |
| **Async Handling** | Kotlin Coroutines, Flows |
| **Database** | Room |
| **Network** | Retrofit |
| **AI/LLM Integration** | OpenAI Whisper & GPT models |
| **Testing** | JUnit, Mockito, Espresso (optional) |

---

## ⚙️ Setup Instructions

### 1. Clone the Repository
```bash
git clone https://github.com/SeethaIndiran/Twin-Mind-Assignment-Recording-App.git
cd Twin-Mind-Assignment-Recording-App

com.example.twinmindrecordingapphomeassignment/
│
├── di/                    # Hilt modules
├── data/
│   ├── model/             # Data models (Recording, Transcript, Summary)
│   ├── repository/        # Repository implementations
│   └── local/             # Room database + DAO
│
├── domain/
│   ├── usecase/           # Business logic and use cases
│
├── ui/
│   ├── screens/           # Compose UI screens
│   ├── viewmodel/         # ViewModels for each feature
│
├── service/
│   └── RecordingService.kt # Foreground service for recording
│
└── util/                  # Common utility functions


## 🧠 How the App Works

1. **Start Recording**
   - User taps the **Record** button on the main screen.
   - The app starts the `RecordingService` in the foreground to safely record audio.
   - Audio is temporarily saved as a file on the device.

2. **Stop Recording**
   - User taps the **Stop** button.
   - Recording file is finalized and saved in the local Room Database along with metadata (date, time, duration).

3. **Transcription**
   - The saved audio file is sent to **OpenAI Whisper API**.
   - Whisper processes the audio and returns a **text transcript**.
   - The transcript is displayed live in the app as it is generated.

4. **Summary Generation**
   - Once transcription is complete, the text is sent to **OpenAI GPT** for summarization.
   - GPT returns a concise summary capturing key points.
   - The summary is stored in Room Database and displayed in the UI.

5. **View & Manage Recordings**
   - Users can view all recordings, transcripts, and summaries.
   - Each recording can be selected to see details (audio playback, transcript, summary).
   - Users can delete recordings or summaries if needed.

6. **Real-Time Feedback**
   - Kotlin Coroutines and Flows update the UI instantly.
   - Users can see progress indicators while transcription and summary are in progress.


## 💡 Future Improvements

- Add **cloud sync** for recordings and summaries using Firebase Storage / Firestore.  
- Enhance **real-time transcription** with streaming API support.  
- Support **multi-language transcription** for global users.  
- Implement **sharing and export** functionality for summaries and transcripts.  
- Add **search and filter** options for recordings and summaries.  
- Improve **UI/UX** with dark mode and customizable themes.  
