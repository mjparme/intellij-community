// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.credentialStore

import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.jna.DisposableMemory
import com.intellij.util.text.nullize
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

// Matching by name fails on locked keyring, because matches against `org.freedesktop.Secret.Generic`
// https://mail.gnome.org/archives/gnome-keyring-list/2015-November/msg00000.html
private const val SECRET_SCHEMA_DONT_MATCH_NAME = 2
private const val SECRET_SCHEMA_ATTRIBUTE_STRING = 0

// explicitly create pointer to be explicitly dispose it to avoid sensitive data in the memory
internal fun stringPointer(data: ByteArray, clearInput: Boolean = false): DisposableMemory {
  val pointer = DisposableMemory(data.size + 1L)
  pointer.write(0, data, 0, data.size)
  pointer.setByte(data.size.toLong(), 0.toByte())
  if (clearInput) {
    data.fill(0)
  }
  return pointer
}

// we use default collection, it seems no way to use custom
internal class SecretCredentialStore private constructor(schemeName: String) : CredentialStore {
  private val serviceAttributeNamePointer by lazy { stringPointer("service".toByteArray()) }
  private val accountAttributeNamePointer by lazy { stringPointer("account".toByteArray()) }

  companion object {
    // no need to load lazily - if store created, then it will be used
    // and for clients better to get error earlier, in creation place
    private val library = Native.load("secret-1", SecretLibrary::class.java)

    fun create(schemeName: String): SecretCredentialStore? {
      if (!pingService()) return null
      return SecretCredentialStore(schemeName)
    }

    private fun pingService(): Boolean {
      var attr: DisposableMemory? = null
      var dummySchema: Pointer? = null
      try {
        attr = stringPointer("ij-dummy-attribute".toByteArray())
        dummySchema = library.secret_schema_new("IJ.dummy.ping.schema", SECRET_SCHEMA_DONT_MATCH_NAME,
                                                attr, SECRET_SCHEMA_ATTRIBUTE_STRING,
                                                null)

        val errorRef = arrayOf<GErrorStruct?>(null)
        library.secret_password_lookup_sync(dummySchema, null, errorRef, attr, attr)
        val error = errorRef[0]
        if (error == null) return true
        if (isNoSecretService(error)) return false
      }
      finally {
        attr?.dispose()
        dummySchema?.let { library.secret_schema_unref(it) }
      }
      return true
    }
  }

  private val schema by lazy {
    library.secret_schema_new(schemeName, SECRET_SCHEMA_DONT_MATCH_NAME,
                              serviceAttributeNamePointer, SECRET_SCHEMA_ATTRIBUTE_STRING,
                              accountAttributeNamePointer, SECRET_SCHEMA_ATTRIBUTE_STRING,
                              null)
  }

  override fun get(attributes: CredentialAttributes): Credentials? {
    return CompletableFuture.supplyAsync(Supplier {
      val userName = attributes.userName.nullize()
      checkError("secret_password_lookup_sync") { errorRef ->
        val serviceNamePointer = stringPointer(attributes.serviceName.toByteArray())
        if (userName == null) {
          library.secret_password_lookup_sync(schema, null, errorRef, serviceAttributeNamePointer, serviceNamePointer, null)?.let {
            // Secret Service doesn't allow to get attributes, so, we store joined data
            return@Supplier splitData(it)
          }
        }
        else {
          library.secret_password_lookup_sync(schema, null, errorRef,
                                              serviceAttributeNamePointer, serviceNamePointer,
                                              accountAttributeNamePointer, stringPointer(userName.toByteArray()),
                                              null)?.let {
            return@Supplier splitData(it)
          }
        }
      }
    }, AppExecutorUtil.getAppExecutorService())
      .get(30 /* on Linux first access to keychain can cause system unlock dialog, so, allow user to input data */, TimeUnit.SECONDS)
  }

  override fun set(attributes: CredentialAttributes, credentials: Credentials?) {
    val serviceNamePointer = stringPointer(attributes.serviceName.toByteArray())
    val accountName = attributes.userName.nullize() ?: credentials?.userName
    if (credentials.isEmpty()) {
      checkError("secret_password_store_sync") { errorRef ->
        if (accountName == null) {
          library.secret_password_clear_sync(schema, null, errorRef,
                                             serviceAttributeNamePointer, serviceNamePointer,
                                             null)
        }
        else {
          library.secret_password_clear_sync(schema, null, errorRef,
                                             serviceAttributeNamePointer, serviceNamePointer,
                                             accountAttributeNamePointer, stringPointer(accountName.toByteArray()),
                                             null)
        }
      }
      return
    }

    val passwordPointer = stringPointer(credentials!!.serialize(!attributes.isPasswordMemoryOnly), true)
    checkError("secret_password_store_sync") { errorRef ->
      try {
        if (accountName == null) {
          library.secret_password_store_sync(schema, null, serviceNamePointer, passwordPointer, null, errorRef,
                                             serviceAttributeNamePointer, serviceNamePointer,
                                             null)
        }
        else {
          library.secret_password_store_sync(schema, null, serviceNamePointer, passwordPointer, null, errorRef,
                                             serviceAttributeNamePointer, serviceNamePointer,
                                             accountAttributeNamePointer, stringPointer(accountName.toByteArray()),
                                             null)
        }
      }
      finally {
        passwordPointer.dispose()
      }
    }
  }
}

private inline fun <T> checkError(method: String, task: (errorRef: Array<GErrorStruct?>) -> T): T {
  val errorRef = arrayOf<GErrorStruct?>(null)
  val result = task(errorRef)
  val error = errorRef[0]
  if (error != null && error.code != 0) {
    if (isNoSecretService(error)) {
      LOG.warn("gnome-keyring not installed or kde doesn't support Secret Service API. $method error code ${error.code}, error message ${error.message}")
    }
    else {
      LOG.error("$method error code ${error.code}, error message ${error.message}")
    }
  }
  return result
}

private fun isNoSecretService(error: GErrorStruct) =
  error.code == 32584 || error.code == 32618 || error.code == 32606 || error.code == 32642

// we use sync API to simplify - client will use postponed write
@Suppress("FunctionName")
private interface SecretLibrary : Library {
  fun secret_schema_new(name: String, flags: Int, vararg attributes: Any?): Pointer
  fun secret_schema_unref(schema: Pointer)

  fun secret_password_store_sync(scheme: Pointer, collection: Pointer?, label: Pointer, password: Pointer, cancellable: Pointer?, error: Array<GErrorStruct?>, vararg attributes: Pointer?)

  fun secret_password_lookup_sync(scheme: Pointer, cancellable: Pointer?, error: Array<GErrorStruct?>, vararg attributes: Pointer?): String?

  fun secret_password_clear_sync(scheme: Pointer, cancellable: Pointer?, error: Array<GErrorStruct?>, vararg attributes: Pointer?)
}

@Suppress("unused")
@Structure.FieldOrder("domain", "code", "message")
internal class GErrorStruct : Structure() {
  @JvmField var domain = 0
  @JvmField var code = 0
  @JvmField var message: String? = null
}