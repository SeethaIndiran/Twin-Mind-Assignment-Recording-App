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
        return  "" // Store this securely
    }

    @Provides
    @Singleton
    fun provideWhisperApiService(@Named("openai_api_key") apiKey: String): WhisperApiService {
        return WhisperApiService(apiKey)
    }
}