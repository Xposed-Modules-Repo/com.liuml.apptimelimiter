package com.liuml.apptimelimiter

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.liuml.apptimelimiter.data.AppRule
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.ipc.RuleAccessBridgeService
import com.liuml.apptimelimiter.ipc.RuleContract
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RuleProviderAccessInstrumentedTest {
    @Test
    fun externalClientCanBootstrapRuleProviderAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ownerContext = instrumentation.targetContext
        val clientContext = instrumentation.context
        val clientPackage = clientContext.packageName
        val repository = RuleRepository(ownerContext)
        assertTrue(
            repository.save(
                AppRule(
                    packageName = clientPackage,
                    enabled = true,
                    perLaunchEnabled = true,
                    perLaunchLimitSeconds = 60L,
                ),
            ),
        )
        val accessFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ownerContext.revokeUriPermission(clientPackage, RuleContract.CONTENT_URI, accessFlags)

        val connected = CountDownLatch(1)
        var bridgeBinder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                bridgeBinder = service
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = clientContext.bindService(
            Intent().setClassName(
                ownerContext.packageName,
                RuleAccessBridgeService::class.java.name,
            ),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        try {
            assertTrue(bound)
            assertTrue(connected.await(3L, TimeUnit.SECONDS))
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(RuleAccessBridgeService.DESCRIPTOR)
                request.writeString(clientPackage)
                assertTrue(
                    bridgeBinder?.transact(
                        RuleAccessBridgeService.TRANSACTION_ENSURE_ACCESS,
                        request,
                        reply,
                        0,
                    ) == true,
                )
                reply.readException()
                assertTrue(reply.readInt() != 0)
            } finally {
                request.recycle()
                reply.recycle()
            }
            assertTrue(
                clientContext.contentResolver.call(
                    RuleContract.CONTENT_URI,
                    RuleContract.METHOD_GET_RULE,
                    clientPackage,
                    null,
                )?.getBoolean(RuleContract.KEY_OK, false) == true,
            )
        } finally {
            if (bound) runCatching { clientContext.unbindService(connection) }
            repository.save(AppRule(packageName = clientPackage))
        }
    }
}
