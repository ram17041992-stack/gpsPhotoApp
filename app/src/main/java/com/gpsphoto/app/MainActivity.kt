package com.gpsphoto.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gpsphoto.app.data.PhotoFiles
import com.gpsphoto.app.ui.AppTheme
import com.gpsphoto.app.ui.DetailScreen
import com.gpsphoto.app.ui.GalleryScreen
import com.gpsphoto.app.ui.MapScreen
import com.gpsphoto.app.ui.PhotoViewModel

private const val GALLERY = "gallery"
private const val MAP = "map"
private const val PHOTO = "photo/{id}"

private val mediaPermissions = arrayOf(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.ACCESS_MEDIA_LOCATION,
)
private val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun hasMediaAccess(context: Context) =
    context.checkSelfPermission(mediaPermissions[0]) == PackageManager.PERMISSION_GRANTED

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { AppRoot() } }
    }
}

@Composable
private fun AppRoot(vm: PhotoViewModel = viewModel()) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(hasMediaAccess(context)) }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasAccess = hasMediaAccess(context)
        if (hasAccess) vm.sync()
    }
    LaunchedEffect(Unit) { if (!hasAccess) askMedia.launch(mediaPermissions) }
    // Picks up photos taken or deleted while the app was in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasAccess = hasMediaAccess(context)
        if (hasAccess) vm.sync()
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Camera: ask for location (for the GPS tag), then open the phone's camera app.
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pendingCapture?.let { vm.onCaptured(Uri.parse(it), ok) }
        pendingCapture = null
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val uri = PhotoFiles.newCaptureUri(context) ?: return@rememberLauncherForActivityResult
        pendingCapture = uri.toString()
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            PhotoFiles.discard(context, uri)
            pendingCapture = null
            vm.messages.tryEmit("No camera app found on this phone")
        }
    }

    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val selection by vm.selection.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (hasAccess && route == GALLERY && selection.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { askLocation.launch(locationPermissions) },
                    icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                    text = { Text("Camera") },
                )
            }
        },
        bottomBar = {
            if (hasAccess && (route == GALLERY || route == MAP)) {
                NavigationBar {
                    Tab(Icons.Default.PhotoLibrary, "Photos", route == GALLERY) { nav.openTab(GALLERY) }
                    Tab(Icons.Default.Map, "Map", route == MAP) { nav.openTab(MAP) }
                }
            }
        },
    ) { padding ->
        if (!hasAccess) {
            PermissionPrompt(Modifier.padding(padding)) { askMedia.launch(mediaPermissions) }
            return@Scaffold
        }
        NavHost(nav, startDestination = GALLERY, modifier = Modifier.padding(padding)) {
            composable(GALLERY) {
                GalleryScreen(vm, onOpen = { nav.navigate("photo/$it") })
            }
            composable(MAP) {
                MapScreen(
                    vm,
                    onOpen = { nav.navigate("photo/$it") },
                    onShowInGallery = { place ->
                        vm.placeFilter.value = place
                        nav.openTab(GALLERY)
                    },
                )
            }
            composable(PHOTO, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                DetailScreen(
                    vm,
                    photoId = entry.arguments?.getLong("id") ?: 0L,
                    onBack = { nav.popBackStack() },
                    onShowOnMap = { cellKey ->
                        vm.mapFocus.value = cellKey
                        nav.openTab(MAP)
                    },
                )
            }
        }
    }
}

/** Tabs share one back stack rooted at the gallery, so Back always returns to Photos. */
private fun NavController.openTab(route: String) = navigate(route) {
    popUpTo(GALLERY)
    launchSingleTop = true
}

@Composable
private fun RowScope.Tab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
    )
}

@Composable
private fun PermissionPrompt(modifier: Modifier, onAllow: () -> Unit) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize().padding(32.dp),
    ) {
        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(72.dp))
        Text(
            "To show your photos and where they were taken, allow access to photos and their locations.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(onClick = onAllow) { Text("Allow access") }
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }) { Text("Open app settings") }
    }
}
