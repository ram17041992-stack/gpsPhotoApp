package com.gpsphoto.app.camera

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.gpsphoto.app.data.DeviceLocation
import com.gpsphoto.app.ui.PlaceNameDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val cameraPermissions = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
private fun Context.hasLocation() =
    granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

private val photoSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(ResolutionStrategy(Size(2560, 1920), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
    .build()
private val previewSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .build()

@Composable
fun CameraScreen(onSaved: (Boolean) -> Unit, onOpenPhoto: (Long) -> Unit, onOpenGallery: () -> Unit) {
    val vm: CameraViewModel = viewModel()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCamera by remember { mutableStateOf(context.granted(Manifest.permission.CAMERA)) }
    var hasLocation by remember { mutableStateOf(context.hasLocation()) }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasCamera = context.granted(Manifest.permission.CAMERA)
        hasLocation = context.hasLocation()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasCamera = context.granted(Manifest.permission.CAMERA)
        hasLocation = context.hasLocation()
    }

    // Live GPS while the camera is on screen.
    LaunchedEffect(hasLocation) {
        if (hasLocation) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            DeviceLocation.updates(context).collect { vm.onLocation(it) }
        }
    }

    var lensFacing by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by rememberSaveable { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var cameraError by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        } }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setResolutionSelector(photoSelector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    LaunchedEffect(flashMode) { imageCapture.flashMode = flashMode }

    DisposableEffect(hasCamera, lensFacing) {
        var provider: ProcessCameraProvider? = null
        if (hasCamera) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val p = future.get().also { provider = it }
                val preview = Preview.Builder().setResolutionSelector(previewSelector).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                cameraError = runCatching {
                    p.unbindAll()
                    p.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                }.isFailure
                if (cameraError && lensFacing != CameraSelector.LENS_FACING_BACK) lensFacing = CameraSelector.LENS_FACING_BACK
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose { provider?.unbindAll() }
    }

    // Photos come out upright even though the screen stays in portrait.
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    val settings by vm.settings.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val lastPhoto by vm.lastPhoto.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(5_000)
        }
    }
    val savedPlaces by vm.savedPlaces.collectAsStateWithLifecycle()
    val placeHere = remember(info.location, savedPlaces) { vm.placeHere(info.location) }
    var namingPlace by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val shutterFlash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
            }) {
                Icon(
                    when (flashMode) {
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                        ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                        else -> Icons.Default.FlashOff
                    },
                    contentDescription = "Flash",
                    tint = Color.White,
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TemplateMenu(settings.template) { t -> vm.updateSettings { it.copy(template = t) } }
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Tune, contentDescription = "Stamp settings", tint = Color.White)
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            if (hasCamera) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
            val minute = now / 60_000
            val stamp = remember(info, settings, minute, widthPx, placeHere) {
                StampRenderer.render(widthPx, stampContent(info, settings, now, placeHere), settings.template).asImageBitmap()
            }
            Image(
                bitmap = stamp,
                contentDescription = "Photo stamp preview",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            ) {
                when {
                    !hasLocation -> StatusPill("Location is off: photos won't get GPS", "Allow") {
                        askPermissions.launch(cameraPermissions)
                    }
                    info.location == null -> StatusPill("Getting GPS fix…", null, null)
                    else -> StatusPill("GPS ±${info.location!!.accuracy.toInt()} m", null, null)
                }
                if (info.location != null) {
                    PlaceButton(placeHere?.name, Modifier.padding(top = 8.dp)) { namingPlace = true }
                }
            }

            if (!hasCamera) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                ) {
                    Text("Allow the camera to take GPS photos.", color = Color.White, textAlign = TextAlign.Center)
                    Button(onClick = { askPermissions.launch(cameraPermissions) }, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Allow camera")
                    }
                }
            } else if (cameraError) {
                Text(
                    "Camera couldn't start. Close other camera apps and try again.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = shutterFlash.value)))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable { lastPhoto?.let { onOpenPhoto(ContentUris.parseId(it)) } ?: onOpenGallery() },
            ) {
                val uri = lastPhoto
                if (uri != null) {
                    AsyncImage(model = uri, contentDescription = "Last photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Photos", tint = Color.White)
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .semantics { contentDescription = "Take photo" }
                    .clickable(enabled = hasCamera && !busy, role = Role.Button) {
                        scope.launch {
                            shutterFlash.snapTo(0.7f)
                            shutterFlash.animateTo(0f, tween(250))
                        }
                        vm.capture(imageCapture, onSaved)
                    },
            ) {
                Box(Modifier.size(62.dp).clip(CircleShape).background(Color.White))
                if (busy) CircularProgressIndicator(modifier = Modifier.size(40.dp))
            }
            IconButton(
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
                    else CameraSelector.LENS_FACING_BACK
                },
                modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
            ) { Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera", tint = Color.White) }
        }
    }

    if (showSettings) StampSettingsSheet(settings, vm::updateSettings) { showSettings = false }
    if (namingPlace) {
        val existing = placeHere
        PlaceNameDialog(
            existingName = existing?.name,
            suggestion = info.place,
            onDismiss = { namingPlace = false },
            onRemove = {
                existing?.let(vm::removePlace)
                namingPlace = false
            },
            onSave = { name ->
                vm.namePlace(existing, name)
                namingPlace = false
            },
        )
    }
}

@Composable
private fun TemplateMenu(current: Template, onPick: (Template) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            modifier = Modifier.background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(50)),
        ) {
            Text("Template: ${current.label}")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Template.entries.forEach { t ->
                DropdownMenuItem(text = { Text(t.label) }, onClick = { onPick(t); open = false })
            }
        }
    }
}

/** Shows the user's name for this spot, or offers to name it. */
@Composable
private fun PlaceButton(name: String?, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (name != null) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f),
        contentColor = if (name != null) MaterialTheme.colorScheme.onPrimary else Color.White,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Icon(Icons.Default.EditLocationAlt, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                if (name != null) "$name · Rename" else "Name this place",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, action: String?, onAction: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = if (action != null) 4.dp else 12.dp)) {
            Icon(Icons.Default.GpsFixed, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp))
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}
