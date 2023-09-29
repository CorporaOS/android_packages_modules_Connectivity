package com.android.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.INetworkMonitor
import android.net.INetworkMonitorCallbacks
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkAgent
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkProvider
import android.net.NetworkRequest
import android.net.NetworkScore
import android.net.NetworkTestResultParcelable
import android.net.networkstack.NetworkStackClientBase
import android.os.HandlerThread
import com.android.modules.utils.build.SdkLevel
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import com.android.testutils.TestableNetworkCallback
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.verify
import org.mockito.stubbing.Answer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.fail

private inline fun <reified T> ArgumentCaptor() = ArgumentCaptor.forClass(T::class.java)

private val agentCounter = AtomicInteger(1)
private fun nextAgentId() = agentCounter.getAndIncrement()

/**
 * A wrapper for network agents, for use with CSTest.
 *
 * This class knows how to interact with CSTest and has helpful methods to make fake agents
 * that can be manipulated directly from a test.
 */
class CSAgentWrapper(
        val context: Context,
        csHandlerThread: HandlerThread,
        networkStack: NetworkStackClientBase,
        nac: NetworkAgentConfig,
        val nc: NetworkCapabilities,
        val lp: LinkProperties,
        val score: FromS<NetworkScore>,
        val provider: NetworkProvider?
) : TestableNetworkCallback.HasNetwork {
    private val TAG = "CSAgent${nextAgentId()}"
    private val VALIDATION_RESULT_INVALID = 0
    private val VALIDATION_TIMESTAMP = 1234L
    private val agent: NetworkAgent
    private val nmCallbacks: INetworkMonitorCallbacks
    val networkMonitor = mock<INetworkMonitor>()

    override val network: Network get() = agent.network!!

    init {
        // Capture network monitor callbacks and simulate network monitor
        val validateAnswer = Answer {
            CSTest.CSTestExecutor.execute { onValidationRequested() }
            null
        }
        doAnswer(validateAnswer).`when`(networkMonitor).notifyNetworkConnected(any(), any())
        doAnswer(validateAnswer).`when`(networkMonitor).notifyNetworkConnectedParcel(any())
        doAnswer(validateAnswer).`when`(networkMonitor).forceReevaluation(anyInt())
        val nmNetworkCaptor = ArgumentCaptor<Network>()
        val nmCbCaptor = ArgumentCaptor<INetworkMonitorCallbacks>()
        doNothing().`when`(networkStack).makeNetworkMonitor(
                nmNetworkCaptor.capture(),
                any() /* name */,
                nmCbCaptor.capture())

        // Create the actual agent. NetworkAgent is abstract, so make an anonymous subclass.
        if (SdkLevel.isAtLeastS()) {
            agent = object : NetworkAgent(context, csHandlerThread.looper, TAG,
                    nc, lp, score.value, nac, provider) {}
        } else {
            agent = object : NetworkAgent(context, csHandlerThread.looper, TAG,
                    nc, lp, 50 /* score */, nac, provider) {}
        }
        agent.register()
        assertEquals(agent.network!!.netId, nmNetworkCaptor.value.netId)
        nmCallbacks = nmCbCaptor.value
        nmCallbacks.onNetworkMonitorCreated(networkMonitor)
    }

    private fun onValidationRequested() {
        if (SdkLevel.isAtLeastT()) {
            verify(networkMonitor).notifyNetworkConnectedParcel(any())
        } else {
            verify(networkMonitor).notifyNetworkConnected(any(), any())
        }
        nmCallbacks.notifyProbeStatusChanged(0 /* completed */, 0 /* succeeded */)
        val p = NetworkTestResultParcelable()
        p.result = VALIDATION_RESULT_INVALID
        p.probesAttempted = 0
        p.probesSucceeded = 0
        p.redirectUrl = null
        p.timestampMillis = VALIDATION_TIMESTAMP
        nmCallbacks.notifyNetworkTestedWithExtras(p)
    }

    fun connect() {
        val mgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().clearCapabilities()
                .addTransportType(nc.transportTypes[0])
                .build()
        val cb = TestableNetworkCallback()
        mgr.registerNetworkCallback(request, cb)
        agent.markConnected()
        if (null == cb.poll { it is Available && agent.network == it.network }) {
            if (!nc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED) &&
                    nc.hasTransport(TRANSPORT_CELLULAR)) {
                // ConnectivityService adds NOT_SUSPENDED by default to all non-cell agents. An
                // agent without NOT_SUSPENDED will not connect, instead going into the SUSPENDED
                // state, so this call will not terminate.
                // Instead of forcefully adding NOT_SUSPENDED to all agents like older tools did,
                // it's better to let the developer manage it as they see fit but help them
                // debug if they forget.
                fail("Could not connect the agent. Did you forget to add " +
                        "NET_CAPABILITY_NOT_SUSPENDED ?")
            }
            fail("Could not connect the agent. Instrumentation failure ?")
        }
        mgr.unregisterNetworkCallback(cb)
    }

    fun disconnect() {
        agent.unregister()
    }
}
