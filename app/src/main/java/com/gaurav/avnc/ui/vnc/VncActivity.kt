/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.util.Rational
import android.view.*
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.gaurav.avnc.R
import com.gaurav.avnc.databinding.ActivityVncBinding
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.util.Experimental
import com.gaurav.avnc.util.SamsungDex
import com.gaurav.avnc.viewmodel.VncViewModel
import com.gaurav.avnc.vnc.VncUri
import com.synaptics.hidevent.V1_0.IHidevent
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference

/********** [VncActivity] startup helpers *********************************/

private const val PROFILE_KEY = "com.gaurav.avnc.server_profile"

fun startVncActivity(source: Activity, profile: ServerProfile) {
    val intent = Intent(source, VncActivity::class.java)
    intent.putExtra(PROFILE_KEY, profile)
    source.startActivity(intent)
}

fun startVncActivity(source: Activity, uri: VncUri) {
    startVncActivity(source, uri.toServerProfile())
}

/**************************************************************************/


/**
 * This activity handles the connection to a VNC server.
 */
class VncActivity : AppCompatActivity() {

    private val profile by lazy { loadProfile() }
    val viewModel by viewModels<VncViewModel>()
    lateinit var binding: ActivityVncBinding
    private val dispatcher by lazy { Dispatcher(this) }
    val touchHandler by lazy { TouchHandler(viewModel, dispatcher) }
    val keyHandler by lazy { KeyHandler(dispatcher, profile.keyCompatMode, viewModel.pref) }
    private val virtualKeys by lazy { VirtualKeys(this) }
    private var mIHidevent: IHidevent?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_vnc)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this

        setupLayout()

        binding.frameView.initialize(this)
        binding.retryConnectionBtn.setOnClickListener { retryConnection() }

        //Drawers
        setupDrawerLayout()
        binding.keyboardBtn.setOnClickListener { showKeyboard(); closeDrawers() }
        binding.zoomResetBtn.setOnClickListener { viewModel.resetZoom(); closeDrawers() }
        binding.virtualKeysBtn.setOnClickListener { virtualKeys.show(); closeDrawers() }

        //ViewModel setup
        viewModel.frameViewRef = WeakReference(binding.frameView)
        viewModel.credentialRequest.observe(this) { showCredentialDialog() }
        viewModel.sshHostKeyVerifyRequest.observe(this) { showHostKeyDialog() }
        viewModel.state.observe(this) { onClientStateChanged(it) }
        viewModel.initConnection(profile) //Should be called after observers has been setup

        // add for alex
        // todo : need code refactoring
        playHdmi(true)


        try {
            mIHidevent = IHidevent.getService()
            Log.e("alex", "getService")
            mIHidevent?.hubDeviceOpen()
            Log.e("alex", "hubDeviceOpen")
            val mode: Byte = 0
            val rValue: Int = mIHidevent?.hubDeviceWriteEvent(mode)?:-1
            Log.e("alex", "hubDeviceWriteEvent 0:$rValue")
        } catch (e: RemoteException) {
            Log.e("alex", "fail to get hidevent HAL service: $e")
        } catch (e: NoSuchElementException) {
            Log.e("alex", "fail to get hidevent HAL service: $e")
        }

        binding.tvView.setOnTouchListener(FloatingListener())

        binding.backBtn.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                finish()
            }
        } )

    }

    inner class FloatingListener : View.OnTouchListener {
        private var mTouchStartX = 0
        private var mTouchStartY = 0
        private var mTouchCurrentX = 0
        private var mTouchCurrentY = 0

        private var mStartX = 0
        private var mStartY = 0
        private var mStopX = 0
        private var mStopY = 0

        private var isMove = false
        private var rawEvent = IntArray(3)
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val action = event.action
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.e("alex", "onTouchEvent ACTION_DOWN")
                    isMove = false
                    mTouchStartX = event.rawX.toInt()
                    mTouchStartY = event.rawY.toInt()
                    mStartX = event.x.toInt()
                    mStartY = event.y.toInt()

                    rawEvent.set(0,1)
                    rawEvent.set(1,mStartX)
                    rawEvent.set(2,mStartY)
                }
                MotionEvent.ACTION_MOVE -> {
                    Log.e("alex", "onTouchEvent ACTION_MOVE")
                    mTouchCurrentX = event.rawX.toInt()
                    mTouchCurrentY = event.rawY.toInt()
                    mTouchStartX = mTouchCurrentX
                    mTouchStartY = mTouchCurrentY

                    rawEvent.set(0,1)
                    rawEvent.set(1,event.x.toInt())
                    rawEvent.set(2,event.y.toInt())
                }
                MotionEvent.ACTION_UP -> {
                    Log.e("alex", "onTouchEvent ACTION_UP" + event.x + ":" + event.y)
                    mStopX = event.x.toInt()
                    mStopY = event.y.toInt()

                    rawEvent.set(0,0);
                    rawEvent.set(1,mStopX)
                    rawEvent.set(2,mStopY)
                    if (Math.abs(mStopX) <= 1920 - 200) {
                        isMove = true
                    }
                    if (Math.abs(mStopY) <= 1080 - 200) {
                        isMove = true
                    }
                }
                else -> {}
            }

            try {
                mIHidevent?.hidDeviceWriteEvent(rawEvent.get(0), rawEvent.get(1), rawEvent.get(2))
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
            Log.e("alex", "hidDeviceWriteEvent :" + rawEvent.get(0) + ":" + rawEvent.get(1) + ":" + rawEvent.get(2))
            return isMove
        }
    }


    override fun onResume() {
        super.onResume()
        viewModel.sendClipboardText()
    }

    override fun onStart() {
        super.onStart()
        binding.frameView.onResume()
    }

    override fun onStop() {
        super.onStop()

        cleanUp()
        playHdmi(false)
        val mode: Byte = 1
        var rValue = 0
        try {
            rValue = mIHidevent?.hubDeviceWriteEvent(mode)?:-1
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
        Log.e("alex", "hubDeviceWriteEvent 1:$rValue")
        try {
            mIHidevent?.hubDeviceClose()
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
        Log.e("alex", "hubDeviceClose")
        binding.frameView.onPause()
    }

    private fun playHdmi(start: Boolean) {
        binding.tvView.reset()
        if (start) {
            val tvInputManager = getSystemService(TV_INPUT_SERVICE) as TvInputManager
            val list = tvInputManager.tvInputList
            val uri = TvContract.buildChannelUriForPassthroughInput(list[0].id)
            binding.tvView.tune(list[0].id, uri)
        }
    }

    private fun cleanUp() {
        virtualKeys.releaseMetaKeys()
    }

    private fun loadProfile(): ServerProfile {
        val profile = intent.getParcelableExtra<ServerProfile>(PROFILE_KEY)
        if (profile != null) {
            // Make a copy to avoid modifying intent's instance,
            // because we may need the original if we have to retry connection.
            return profile.copy()
        }

        Log.e(javaClass.simpleName, "No connection information was passed through Intent.")
        return ServerProfile()
    }

    private fun retryConnection() {
        //We simply create a new activity to force creation of new ViewModel
        //which effectively restarts the connection.
        if (!isFinishing) {
            startActivity(intent)
            finish()
        }
    }

    private fun showCredentialDialog() {
        CredentialFragment().show(supportFragmentManager, "CredentialDialog")
    }

    private fun showHostKeyDialog() {
        HostKeyFragment().show(supportFragmentManager, "HostKeyFragment")
    }

    fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        binding.frameView.requestFocus()
        imm.showSoftInput(binding.frameView, 0)

        virtualKeys.onKeyboardOpen()
    }

    private fun closeDrawers() = binding.drawerLayout.closeDrawers()

    private fun onClientStateChanged(newState: VncViewModel.State) {
        if (newState == VncViewModel.State.Connected) {

            if (!viewModel.pref.runInfo.hasConnectedSuccessfully) {
                viewModel.pref.runInfo.hasConnectedSuccessfully = true

                // Highlight drawer for first time users
                binding.drawerLayout.open()
                lifecycleScope.launchWhenCreated {
                    delay(1500)
                    binding.drawerLayout.close()
                }
            }

            SamsungDex.setMetaKeyCapture(this, true)
        } else {
            SamsungDex.setMetaKeyCapture(this, false)
        }

        updateSystemUiVisibility()
    }

    /************************************************************************************
     * Layout handling.
     ************************************************************************************/

    private val fullscreenMode by lazy { viewModel.pref.viewer.fullscreen }
    private val immersiveMode by lazy { viewModel.pref.experimental.immersiveMode }

    private fun setupLayout() {

        setupOrientation()

        @Suppress("DEPRECATION")
        if (fullscreenMode) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.setOnSystemUiVisibilityChangeListener { updateSystemUiVisibility() }
        }

        binding.frameView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            viewModel.frameState.setWindowSize(v.width.toFloat(), v.height.toFloat())
        }

        //This is used to handle cases where a system view (e.g. soft keyboard) is covering
        //some part of our window. We retrieve the visible area and add padding to our
        //root view so that its content is resized to that area.
        //This will trigger the resize of frame view allowing it to handle the available space.
        val visibleFrame = Rect()
        val rootLocation = intArrayOf(0, 0)
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            binding.root.getWindowVisibleDisplayFrame(visibleFrame)

            // Normally, the root view will cover the whole screen, but on devices
            // with display-cutout it will be letter-boxed by the system.
            // In that case the root view won't start from (0,0).
            // So we have to offset the visibleFame (which is in display coordinates)
            // to make sure it is relative to our root view.
            binding.root.getLocationOnScreen(rootLocation)
            visibleFrame.offset(-rootLocation[0], -rootLocation[1])

            var paddingBottom = binding.root.bottom - visibleFrame.bottom
            if (paddingBottom < 0)
                paddingBottom = 0

            //Try to guess if keyboard is closing
            if (paddingBottom == 0 && binding.root.paddingBottom != 0)
                virtualKeys.onKeyboardClose()

            binding.root.updatePadding(bottom = paddingBottom)
        }
    }

    private fun setupOrientation() {
        requestedOrientation = when (viewModel.pref.viewer.orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun setupDrawerLayout() {
        binding.drawerLayout.setScrimColor(0)

        // Update Toolbar gravity
        val gravityH = if (viewModel.pref.viewer.toolbarAlignment == "start") Gravity.START else Gravity.END

        val lp = binding.primaryToolbar.layoutParams as DrawerLayout.LayoutParams
        lp.gravity = gravityH or Gravity.CENTER_VERTICAL
        binding.primaryToolbar.layoutParams = lp

        if (viewModel.pref.experimental.swipeCloseToolbar)
            Experimental.setupDrawerCloseOnScrimSwipe(binding.drawerLayout, gravityH)
    }


    @Suppress("DEPRECATION")
    private fun updateSystemUiVisibility() {
        if (!fullscreenMode || !immersiveMode)
            return

        val flags = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        window.decorView.apply {
            if (viewModel.client.connected)
                systemUiVisibility = systemUiVisibility or flags
            else
                systemUiVisibility = systemUiVisibility and flags.inv()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSystemUiVisibility()
    }


    /************************************************************************************
     * Picture-in-Picture support
     ************************************************************************************/

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPiPMode()
    }

    override fun onPictureInPictureModeChanged(inPiP: Boolean, newConfig: Configuration?) {
        super.onPictureInPictureModeChanged(inPiP, newConfig)
        if (inPiP) {
            closeDrawers()
            viewModel.resetZoom()
            virtualKeys.hide()
        }
    }

    private fun enterPiPMode() {
        val canEnter = viewModel.pref.viewer.pipEnabled && viewModel.client.connected

        if (canEnter && Build.VERSION.SDK_INT >= 26) {

            val fs = viewModel.frameState
            val aspectRatio = Rational(fs.fbWidth.toInt(), fs.fbHeight.toInt())
            val param = PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build()

            try {
                enterPictureInPictureMode(param)
            } catch (e: IllegalStateException) {
                Log.w(javaClass.simpleName, "Cannot enter PiP mode", e)
            }
        }
    }

    /************************************************************************************
     * Input
     ************************************************************************************/

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return keyHandler.onKeyEvent(event) || workarounds(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return keyHandler.onKeyEvent(event) || workarounds(event) || super.onKeyUp(keyCode, event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        return keyHandler.onKeyEvent(event) || super.onKeyMultiple(keyCode, repeatCount, event)
    }

    private fun workarounds(keyEvent: KeyEvent): Boolean {

        //It seems that some device manufacturers are hell-bent on making developers'
        //life miserable. In their infinite wisdom, they decided that Android apps don't
        //need Mouse right-click events. It is hardcoded to act as back-press, without
        //giving apps a chance to handle it. For better or worse, they set the 'source'
        //for such key events to Mouse, enabling the following workarounds.
        if (keyEvent.keyCode == KeyEvent.KEYCODE_BACK &&
            InputDevice.getDevice(keyEvent.deviceId).supportsSource(InputDevice.SOURCE_MOUSE) &&
            viewModel.pref.input.interceptMouseBack) {
            if (keyEvent.action == KeyEvent.ACTION_DOWN)
                touchHandler.onMouseBack()
            return true
        }
        return false
    }
}