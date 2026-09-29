// parent-app/src/main/java/com/guardianlink/parent/di/ParentAppModule.kt
package com.guardianlink.parent.di

import android.content.Context
import com.guardianlink.parent.data.firebase.ParentFirebaseManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ParentAppModule {

    @Provides
    @Singleton
    fun provideParentFirebaseManager(
        @ApplicationContext context: Context
    ): ParentFirebaseManager = ParentFirebaseManager(context)
}
