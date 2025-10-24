package com.example.twinmindrecordingapphomeassignment.di

import com.example.twinmindrecordingapphomeassignment.data.remote.api.WhisperApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

// NetworkModule.kt
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Named("openai_api_key")
    fun provideOpenAIApiKey(): String {
        return "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" // Store this securely
    }

    @Provides
    @Singleton
    fun provideWhisperApiService(@Named("openai_api_key") apiKey: String): WhisperApiService {
        return WhisperApiService(apiKey)
    }
}