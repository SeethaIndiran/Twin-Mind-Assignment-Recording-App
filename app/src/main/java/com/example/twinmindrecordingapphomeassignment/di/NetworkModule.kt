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
        return  "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" // Store this securely
    }

    @Provides
    @Singleton
    fun provideWhisperApiService(@Named("openai_api_key") apiKey: String): WhisperApiService {
        return WhisperApiService(apiKey)
    }
}