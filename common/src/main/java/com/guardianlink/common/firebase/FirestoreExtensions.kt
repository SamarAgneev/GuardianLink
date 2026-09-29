package com.guardianlink.common.firebase

import com.google.firebase.firestore.DocumentReference
import kotlinx.coroutines.tasks.await

suspend fun DocumentReference.createDocumentIfAbsent(data: Any): Boolean =
    firestore.runTransaction { transaction ->
        if (transaction.get(this).exists()) {
            false
        } else {
            transaction.set(this, data)
            true
        }
    }.await()