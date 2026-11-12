package com.s25.iphotos

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

val IosBlack = Color(0xFF000000)
val IosDarkGray = Color(0xFF1C1C1E)
val IosYellow = Color(0xFFFFD60A)
val IosBlue = Color(0xFF0A84FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = IosBlack, surface = IosDarkGray)) {
                IPhotosApp()
            }
        }
    }
}

@Composable
fun IPhotosApp() {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        )
    }
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            photos = loadGalleryPhotos(context)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = IosBlack) {
        if (!hasPermission) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(
                    onClick = { launcher.launch(Manifest.permission.READ_MEDIA_IMAGES) },
                    colors = ButtonDefaults.buttonColors(containerColor = IosBlue)
                ) {
                    Text("Consenti accesso alle Foto", color = Color.White)
                }
            }
        } else if (selectedPhotoUri != null) {
            PhotoEditorScreen(
                uri = selectedPhotoUri!!,
                onBack = { selectedPhotoUri = null },
                onSaved = {
                    selectedPhotoUri = null
                    photos = loadGalleryPhotos(context)
                }
            )
        } else {
            GalleryGridScreen(
                photos = photos,
                onPhotoClick = { selectedPhotoUri = it }
            )
        }
    }
}

@Composable
fun GalleryGridScreen(photos: List<Uri>, onPhotoClick: (Uri) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Libreria",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Surface(
                color = IosDarkGray,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = "${photos.size} foto • S25",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(photos) { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clickable { onPhotoClick(uri) }
                )
            }
        }
    }
}

enum class ToolTab { ADJUST, FILTERS, CROP }

data class AdjustOption(
    val id: String,
    val label: String,
    val icon: ImageVector
)

@Composable
fun PhotoEditorScreen(uri: Uri, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewSource by remember { mutableStateOf<Bitmap?>(null) }
    var editedPreview by remember { mutableStateOf<Bitmap?>(null) }
    var editParams by remember { mutableStateOf(EditParams()) }
    var showOriginalHold by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(ToolTab.ADJUST) }
    var selectedAdjustId by remember { mutableStateOf("brilliance") }
    var isSaving by remember { mutableStateOf(false) }

    val adjustOptions = remember {
        listOf(
            AdjustOption("exposure", "Esposizione", Icons.Default.Exposure),
            AdjustOption("brilliance", "Brillantezza", Icons.Default.AutoAwesome),
            AdjustOption("highlights", "Alte luci", Icons.Default.WbSunny),
            AdjustOption("shadows", "Ombre", Icons.Default.BrightnessLow),
            AdjustOption("contrast", "Contrasto", Icons.Default.Contrast),
            AdjustOption("saturation", "Saturazione", Icons.Default.Palette),
            AdjustOption("warmth", "Calore", Icons.Default.Thermostat)
        )
    }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val full = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            originalBitmap = full

            val scale = minOf(1400f / full.width, 1400f / full.height, 1f)
            previewSource = if (scale < 1f) {
                Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
            } else full
        }
    }

    LaunchedEffect(previewSource, editParams) {
        val src = previewSource ?: return@LaunchedEffect
        editedPreview = withContext(Dispatchers.Default) {
            ImageEngine.applyEdits(src, editParams)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Annulla",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.clickable { onBack() }
            )

            if (editParams != EditParams()) {
                Text(
                    text = "Ripristina",
                    color = IosYellow,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { editParams = EditParams() }
                )
            }

            Button(
                onClick = {
                    val full = originalBitmap ?: return@Button
                    isSaving = true
                    scope.launch(Dispatchers.IO) {
                        val finalBmp = ImageEngine.applyEdits(full, editParams)
                        ImageEngine.saveToGallery(context, finalBmp)
                        withContext(Dispatchers.Main) {
                            isSaving = false
                            Toast.makeText(context, "Salvata nella Galleria", Toast.LENGTH_SHORT).show()
                            onSaved()
                        }
                    }
                },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = IosYellow),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    text = if (isSaving) "..." else "Fine",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            showOriginalHold = true
                            tryAwaitRelease()
                            showOriginalHold = false
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val displayBmp = if (showOriginalHold) previewSource else editedPreview
            displayBmp?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (showOriginalHold) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                ) {
                    Text(
                        "ORIGINALE",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        when (activeTab) {
            ToolTab.ADJUST -> {
                val currentValue = getParamValue(editParams, selectedAdjustId)
                val currentLabel = adjustOptions.find { it.id == selectedAdjustId }?.label ?: ""

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        items(adjustOptions) { opt ->
                            val value = getParamValue(editParams, opt.id)
                            val isSelected = opt.id == selectedAdjustId
                            val isModified = value != 0f

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { selectedAdjustId = opt.id }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) Color.White else IosDarkGray)
                                        .border(
                                            width = if (isModified && !isSelected) 2.dp else 0.dp,
                                            color = if (isModified) IosYellow else Color.Transparent,
                                            shape = CircleShape
                                        )
                                ) {
                                    Icon(
                                        imageVector = opt.icon,
                                        contentDescription = opt.label,
                                        tint = if (isSelected) Color.Black else Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (isModified) "${value.roundToInt()}" else opt.label,
                                    color = if (isSelected) IosYellow else Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Text(
                        text = "${currentLabel.uppercase()}  ${currentValue.roundToInt()}",
                        color = if (currentValue != 0f) IosYellow else Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IosRulerSlider(
                        value = currentValue,
                        onValueChange = { newVal ->
                            editParams = updateParamValue(editParams, selectedAdjustId, newVal)
                        }
                    )
                }
            }

            ToolTab.FILTERS -> {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    itemsIndexed(ImageEngine.filterNames) { index, name ->
                        val selected = editParams.filterIndex == index
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                editParams = editParams.copy(filterIndex = index)
                            }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(IosDarkGray)
                                    .border(
                                        width = if (selected) 2.dp else 0.dp,
                                        color = IosYellow,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                            ) {
                                Text(
                                    text = name.take(3).uppercase(),
                                    color = if (selected) IosYellow else Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = name,
                                color = if (selected) IosYellow else Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            ToolTab.CROP -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 26.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(
                        onClick = {
                            editParams = editParams.copy(rotation = (editParams.rotation + 90f) % 360f)
                        },
                        border = ButtonDefaults.outlinedButtonBorder
                    ) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Ruota 90°", color = Color.White)
                    }
                    OutlinedButton(
                        onClick = {
                            editParams = editParams.copy(flipHorizontal = !editParams.flipHorizontal)
                        }
                    ) {
                        Icon(Icons.Default.Flip, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Rifletti", color = Color.White)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BottomTabItem("Regola", Icons.Default.Tune, activeTab == ToolTab.ADJUST) {
                activeTab = ToolTab.ADJUST
            }
            BottomTabItem("Filtri", Icons.Default.FilterVintage, activeTab == ToolTab.FILTERS) {
                activeTab = ToolTab.FILTERS
            }
            BottomTabItem("Ritaglia", Icons.Default.CropRotate, activeTab == ToolTab.CROP) {
                activeTab = ToolTab.CROP
            }
        }
    }
}

@Composable
fun BottomTabItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) IosYellow else Color.Gray)
        Spacer(Modifier.height(2.dp))
        Text(label, color = if (selected) IosYellow else Color.Gray, fontSize = 11.sp)
    }
}

@Composable
fun IosRulerSlider(value: Float, onValueChange: (Float) -> Unit) {
    val currentVal by rememberUpdatedState(value)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 24.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    val delta = (dragAmount / size.width) * 200f
                    onValueChange((currentVal + delta).coerceIn(-100f, 100f))
                }
            }
    ) {
        val centerX = size.width / 2f
        val step = size.width / 40f
        val offsetPx = (value / 100f) * (size.width / 2.2f)

        for (i in -20..20) {
            val x = centerX + (i * step) + offsetPx
            if (x in 0f..size.width) {
                val isMajor = i % 5 == 0
                drawLine(
                    color = if (i == 0) IosYellow else Color.DarkGray,
                    start = Offset(x, if (isMajor) 14f else 22f),
                    end = Offset(x, size.height - 14f),
                    strokeWidth = if (i == 0) 4f else 2f
                )
            }
        }
        drawLine(
            color = Color.White,
            start = Offset(centerX, 6f),
            end = Offset(centerX, size.height - 6f),
            strokeWidth = 5f
        )
    }
}

private fun getParamValue(p: EditParams, id: String): Float = when (id) {
    "exposure" -> p.exposure
    "brilliance" -> p.brilliance
    "highlights" -> p.highlights
    "shadows" -> p.shadows
    "contrast" -> p.contrast
    "saturation" -> p.saturation
    "warmth" -> p.warmth
    else -> 0f
}

private fun updateParamValue(p: EditParams, id: String, v: Float): EditParams = when (id) {
    "exposure" -> p.copy(exposure = v)
    "brilliance" -> p.copy(brilliance = v)
    "highlights" -> p.copy(highlights = v)
    "shadows" -> p.copy(shadows = v)
    "contrast" -> p.copy(contrast = v)
    "saturation" -> p.copy(saturation = v)
    "warmth" -> p.copy(warmth = v)
    else -> p
}

private fun loadGalleryPhotos(context: android.content.Context): List<Uri> {
    val uris = mutableListOf<Uri>()
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        sortOrder
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
        }
    }
    return uris
}
