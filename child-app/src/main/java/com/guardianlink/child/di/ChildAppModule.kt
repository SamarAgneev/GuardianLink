// child-app/src/main/java/com/guardianlink/child/di/ChildAppModule.kt
package com.guardianlink.child.di

import android.content.Context
import com.guardianlink.child.firebase.ChildFirebaseManager
import com.guardianlink.child.data.OfflineQueue
import com.guardianlink.child.webrtc.ChildWebRtcClient
import com.guardianlink.common.security.SecurePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChildAppModule {

    @Provides
    @Singleton
    fun provideSecurePreferences(
        @ApplicationContext context: Context
    ): SecurePreferences = SecurePreferences(context)

    @Provides
    @Singleton
    fun provideChildFirebaseManager(
        @ApplicationContext context: Context,
        securePrefs: SecurePreferences,
        offlineQueue: OfflineQueue
    ): ChildFirebaseManager = ChildFirebaseManager(context, securePrefs, offlineQueue)

    @Provides
    @Singleton
    fun provideChildWebRtcClient(
        @ApplicationContext context: Context
    ): ChildWebRtcClient = ChildWebRtcClient(context)
}
