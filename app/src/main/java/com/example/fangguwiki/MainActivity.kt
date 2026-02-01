package com.example.fangguwiki

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.amap.api.maps.AMapUtils
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.services.core.PoiItemV2
import com.amap.api.services.poisearch.PoiResultV2
import com.amap.api.services.poisearch.PoiSearchV2
import com.amap.api.services.geocoder.GeocodeQuery
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.example.fangguwiki.ui.theme.FangguwikiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSSLHandshake()
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
        enableEdgeToEdge()
        setContent { FangguwikiTheme { FangguwikiApp() } }
    }

    private fun handleSSLHandshake() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate>? = null
                override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
            })
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, java.security.SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) { e.printStackTrace() }
    }
}

@Composable
fun FangguwikiApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.MAP) }
    var focusPinId by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val database = remember { HeritageDatabase.getDatabase(context) }
    val heritageDao = database.heritageDao()
    
    var hasLocationPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = { Icon(it.icon, contentDescription = it.label) },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentDestination) {
                    AppDestinations.MAP -> MapScreen(
                        hasLocationPermission = hasLocationPermission,
                        heritageDao = heritageDao,
                        focusPinId = focusPinId,
                        onFocusHandled = { focusPinId = null }
                    )
                    AppDestinations.LIST -> ListScreen(
                        heritageDao = heritageDao,
                        onShowOnMap = { pin ->
                            focusPinId = pin.id
                            currentDestination = AppDestinations.MAP
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    hasLocationPermission: Boolean,
    heritageDao: HeritageDao,
    focusPinId: Int?,
    onFocusHandled: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mapView = remember { MapView(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val menuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSheet by remember { mutableStateOf(false) }
    var editingPin by remember { mutableStateOf<HeritagePin?>(null) }
    var selectedLatLng by remember { mutableStateOf<LatLng?>(null) }
    var isSatellite by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var debugInfo by remember { mutableStateOf("") }
    val showDebugOverlay = false
    var lastMarkerLongPressAt by remember { mutableStateOf(0L) }
    val uiHandler = remember { Handler(Looper.getMainLooper()) }
    var tempMarker by remember { mutableStateOf<Marker?>(null) }
    var pinMenuTarget by remember { mutableStateOf<HeritagePin?>(null) }
    var deletePinCandidate by remember { mutableStateOf<HeritagePin?>(null) }

    // 临时 pin 图标缓存（用于长按空白处）
    val tempIconWidthPx = remember(context) {
        (36 * context.resources.displayMetrics.density).toInt()
    }
    val tempIconWidthLargePx = remember(context) {
        (48 * context.resources.displayMetrics.density).toInt()
    }
    val tempIconCache = remember { mutableMapOf<Pair<Int, Int>, BitmapDescriptor>() }
    fun getTempIcon(resId: Int, sizePx: Int): BitmapDescriptor {
        val key = resId to sizePx
        return tempIconCache.getOrPut(key) { getResizedIcon(context, resId, sizePx) }
    }

    val pins by heritageDao.getAllPins().collectAsState(initial = emptyList())

    MapLifecycleHandler(mapView)

    Box(modifier = Modifier.fillMaxSize()) {
        MapViewContainer(
            mapView = mapView,
            pins = pins,
            hasLocationPermission = hasLocationPermission,
            isSatellite = isSatellite,
            focusPinId = focusPinId,
            onFocusHandled = onFocusHandled,
            onMapLongClick = { latLng ->
                val now = SystemClock.uptimeMillis()
                if (now - lastMarkerLongPressAt < 700) {
                    return@MapViewContainer
                }
                // 长按空白处：先弹出临时 pin 动画，再打开编辑界面
                val aMap = mapView.map
                tempMarker?.remove()
                val iconRes = R.drawable.ic_pic_tower_default
                val marker = aMap.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .icon(getTempIcon(iconRes, tempIconWidthLargePx))
                )
                tempMarker = marker
                uiHandler.postDelayed({
                    marker?.setIcon(getTempIcon(iconRes, tempIconWidthPx))
                }, 180)
                uiHandler.postDelayed({
                    selectedLatLng = latLng
                    editingPin = null
                    showSheet = true
                }, 320)
            },
            onMarkerClick = { pin, position ->
                editingPin = pin
                selectedLatLng = position
                showSheet = true
            },
            onMarkerLongClick = { pin ->
                lastMarkerLongPressAt = SystemClock.uptimeMillis()
                pinMenuTarget = pin
            },
            onDebugUpdate = { debugInfo = it }
        )

        // 顶部精美搜索栏 - 模仿图片样式
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 48.dp)
                .height(54.dp)
                .align(Alignment.TopCenter),
            shape = RoundedCornerShape(27.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically, 
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
            ) {
                Icon(
                    Icons.Default.ArrowBack, 
                    contentDescription = null, 
                    tint = Color.Gray, 
                    modifier = Modifier.size(24.dp).clickable { /* 返回逻辑 */ }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f)) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = TextStyle(fontSize = 16.sp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) { 
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp)) 
                    }
                }
                VerticalDivider(
                    modifier = Modifier.height(20.dp).padding(horizontal = 8.dp),
                    thickness = 1.dp,
                    color = Color.LightGray.copy(alpha = 0.5f)
                )
                Text(
                    text = "搜索",
                    color = Color(0xFF007AFF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.clickable {
                        val query = searchQuery.trim()
                        if (query.isEmpty()) {
                            Toast.makeText(context, "请输入搜索内容", Toast.LENGTH_SHORT).show()
                            return@clickable
                        }
                        if (isSearching) return@clickable

                        isSearching = true
                        val aMap = mapView.map
                        // 如果输入看起来像城市名（2-6 个汉字），优先按城市搜索；否则全国搜索
                        val looksLikeCity = query.matches(Regex("^[\\u4e00-\\u9fa5]{2,6}$"))
                        fun runPoiSearch() {
                            val poiQuery = if (looksLikeCity) {
                                PoiSearchV2.Query(query, "", query)
                            } else {
                                PoiSearchV2.Query(query, "", "")
                            }
                            poiQuery.pageNum = 1
                            poiQuery.pageSize = 1
                            val poiSearch = PoiSearchV2(context, poiQuery)
                            poiSearch.setOnPoiSearchListener(object : PoiSearchV2.OnPoiSearchListener {
                                override fun onPoiSearched(result: PoiResultV2?, rCode: Int) {
                                    isSearching = false
                                    if (rCode != 1000 || result == null || result.pois.isNullOrEmpty()) {
                                        Toast.makeText(context, "未找到相关地点", Toast.LENGTH_SHORT).show()
                                        return
                                    }
                                    val poi = result.pois.first()
                                    val latLng = LatLng(poi.latLonPoint.latitude, poi.latLonPoint.longitude)
                                    aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
                                    // 搜索到地点后只移动地图，不自动弹出“新发现”窗口
                                }

                                override fun onPoiItemSearched(item: PoiItemV2?, rCode: Int) {
                                    // Not used.
                                }
                            })
                            poiSearch.searchPOIAsyn()
                        }

                        if (looksLikeCity) {
                            val geocoder = GeocodeSearch(context)
                            geocoder.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                                override fun onGeocodeSearched(result: GeocodeResult?, rCode: Int) {
                                    if (rCode == 1000 && result != null && result.geocodeAddressList.isNotEmpty()) {
                                        isSearching = false
                                        val addr = result.geocodeAddressList.first()
                                        val latLng = LatLng(addr.latLonPoint.latitude, addr.latLonPoint.longitude)
                                        aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 12f))
                                    } else {
                                        runPoiSearch()
                                    }
                                }

                                override fun onRegeocodeSearched(result: com.amap.api.services.geocoder.RegeocodeResult?, rCode: Int) {
                                    // Not used.
                                }
                            })
                            geocoder.getFromLocationNameAsyn(GeocodeQuery(query, query))
                        } else {
                            runPoiSearch()
                        }
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "当前地点",
                    color = Color(0xFF007AFF),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        if (!hasLocationPermission) {
                            Toast.makeText(context, "请先开启定位权限", Toast.LENGTH_SHORT).show()
                            return@clickable
                        }
                        val aMap = mapView.map
                        val myLocationStyle = MyLocationStyle()
                        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATE)
                        aMap.myLocationStyle = myLocationStyle
                        aMap.isMyLocationEnabled = true
                        aMap.uiSettings.isMyLocationButtonEnabled = true
                        // 使用高德定位 SDK 单次定位，避免 myLocation 返回 0,0
                        val client = AMapLocationClient(context)
                        val option = AMapLocationClientOption().apply {
                            isOnceLocation = true
                            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                            isNeedAddress = false
                        }
                        client.setLocationOption(option)
                        client.setLocationListener { loc ->
                            if (loc != null && loc.errorCode == 0) {
                                val latLng = LatLng(loc.latitude, loc.longitude)
                                aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
                                debugInfo = "debug: sdk loc=${loc.latitude},${loc.longitude} acc=${loc.accuracy} provider=${loc.provider}"
                            } else {
                                val code = loc?.errorCode ?: -1
                                val msg = loc?.errorInfo ?: "null"
                                debugInfo = "debug: sdk loc failed code=$code msg=$msg"
                                Toast.makeText(context, "定位失败($code)", Toast.LENGTH_SHORT).show()
                            }
                            client.stopLocation()
                            client.onDestroy()
                        }
                        client.startLocation()
                    }
                )
            }
        }

        FloatingActionButton(
            onClick = { isSatellite = !isSatellite },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            containerColor = Color.White,
            shape = RoundedCornerShape(12.dp)
        ) { Icon(Icons.Default.Layers, contentDescription = null) }

        if (showSheet && selectedLatLng != null) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = sheetState,
                containerColor = Color.White,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                AddPinBottomSheetContent(
                    initialPin = editingPin,
                    onSave = { pin ->
                        scope.launch {
                            if (editingPin != null) {
                                heritageDao.updatePin(
                                    pin.copy(
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            } else {
                                val now = System.currentTimeMillis()
                                heritageDao.insertPin(
                                    pin.copy(
                                        latitude = selectedLatLng!!.latitude,
                                        longitude = selectedLatLng!!.longitude,
                                        createdAt = now,
                                        updatedAt = now
                                    )
                                )
                            }
                            tempMarker?.remove()
                            tempMarker = null
                            showSheet = false
                        }
                    },
                    onDelete = { pin ->
                        scope.launch {
                            heritageDao.deletePin(pin)
                            tempMarker?.remove()
                            tempMarker = null
                            showSheet = false
                        }
                    },
                    onCancelDelete = { pin ->
                        scope.launch {
                            heritageDao.deletePin(pin)
                            tempMarker?.remove()
                            tempMarker = null
                            showSheet = false
                        }
                    },
                    onCancelCreate = {
                        tempMarker?.remove()
                        tempMarker = null
                        showSheet = false
                    }
                )
            }
        }

        if (pinMenuTarget != null) {
            ModalBottomSheet(
                onDismissRequest = { pinMenuTarget = null },
                sheetState = menuSheetState,
                containerColor = Color.White,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    TextButton(
                        onClick = {
                            val pin = pinMenuTarget ?: return@TextButton
                            pinMenuTarget = null
                            editingPin = pin
                            selectedLatLng = LatLng(pin.latitude, pin.longitude)
                            showSheet = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("编辑") }
                    TextButton(
                        onClick = {
                            val pin = pinMenuTarget ?: return@TextButton
                            pinMenuTarget = null
                            deletePinCandidate = pin
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("删除", color = Color.Red) }
                    TextButton(
                        onClick = {
                            val pin = pinMenuTarget ?: return@TextButton
                            pinMenuTarget = null
                            val pkgCandidates = listOf(
                                "com.autonavi.minimap", // AMap / AMap Global
                                "com.autonavi.amap" // AMap Lite (some regions)
                            )
                            val geoUri = Uri.parse(
                                "geo:${pin.latitude},${pin.longitude}?q=${pin.latitude},${pin.longitude}(${Uri.encode(pin.name)})"
                            )
                            var launched = false

                            debugInfo = "debug: nav try geo pkg=${pkgCandidates.joinToString()}"

                            // Prefer geo: with AMap package (AMap can handle geo links)
                            for (pkg in pkgCandidates) {
                                val intent = Intent(Intent.ACTION_VIEW, geoUri).setPackage(pkg)
                                val can = intent.resolveActivity(context.packageManager) != null
                                if (can) {
                                    context.startActivity(intent)
                                    launched = true
                                    break
                                }
                                debugInfo = "debug: geo pkg $pkg cannot resolve"
                            }

                            if (!launched) {
                                val uriCandidates = listOf(
                                    "androidamap://navi?sourceApplication=fangguwiki&poiname=${Uri.encode(pin.name)}&lat=${pin.latitude}&lon=${pin.longitude}&dev=0&style=2",
                                    "androidamap://route/plan/?dlat=${pin.latitude}&dlon=${pin.longitude}&dname=${Uri.encode(pin.name)}&dev=0&t=0",
                                    "amapuri://route/plan/?dlat=${pin.latitude}&dlon=${pin.longitude}&dname=${Uri.encode(pin.name)}&dev=0&t=0"
                                )
                                for (uriStr in uriCandidates) {
                                    val uri = Uri.parse(uriStr)
                                    for (pkg in pkgCandidates) {
                                        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
                                        val can = intent.resolveActivity(context.packageManager) != null
                                        if (can) {
                                            context.startActivity(intent)
                                            launched = true
                                            break
                                        }
                                        debugInfo = "debug: uri $uriStr pkg $pkg cannot resolve"
                                    }
                                    if (launched) break
                                    val intent = Intent(Intent.ACTION_VIEW, uri)
                                    val canAny = intent.resolveActivity(context.packageManager) != null
                                    if (canAny) {
                                        context.startActivity(intent)
                                        launched = true
                                        break
                                    }
                                    debugInfo = "debug: uri $uriStr no handler"
                                }
                            }

                            if (!launched) {
                                val fallbackIntent = Intent(Intent.ACTION_VIEW, geoUri)
                                val can = fallbackIntent.resolveActivity(context.packageManager) != null
                                if (can) {
                                    context.startActivity(fallbackIntent)
                                } else {
                                    Toast.makeText(context, "未安装地图应用", Toast.LENGTH_SHORT).show()
                                    debugInfo = "debug: fallback geo no handler"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("到那里去") }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        if (deletePinCandidate != null) {
            AlertDialog(
                onDismissRequest = { deletePinCandidate = null },
                title = { Text("删除标记？") },
                text = { Text("确定要删除这个地点吗？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val pin = deletePinCandidate ?: return@TextButton
                            scope.launch {
                                heritageDao.deletePin(pin)
                                deletePinCandidate = null
                            }
                        }
                    ) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { deletePinCandidate = null }) { Text("取消") }
                }
            )
        }


        // on-screen debug overlay (hidden by default)
        if (showDebugOverlay && debugInfo.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(debugInfo, color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun MapViewContainer(
    mapView: MapView,
    pins: List<HeritagePin>,
    hasLocationPermission: Boolean,
    isSatellite: Boolean,
    focusPinId: Int?,
    onFocusHandled: () -> Unit,
    onMapLongClick: (LatLng) -> Unit,
    onMarkerClick: (HeritagePin, LatLng) -> Unit,
    onMarkerLongClick: (HeritagePin) -> Unit,
    onDebugUpdate: (String) -> Unit
) {
    val context = LocalContext.current
    val latestPins by rememberUpdatedState(pins)
    val onMapLongClickState by rememberUpdatedState(onMapLongClick)
    val onMarkerClickState by rememberUpdatedState(onMarkerClick)
    val markersById = remember { mutableMapOf<Int, Marker>() }
    val longPressTracker = remember { LongPressTracker(Handler(Looper.getMainLooper())) }

    // 图标缓存，按密度缩放后缓存，避免在循环中重复解码
    val targetMarkerWidthPx = remember(context) {
        (36 * context.resources.displayMetrics.density).toInt()
    }
    val targetMarkerWidthLargePx = remember(context) {
        (44 * context.resources.displayMetrics.density).toInt()
    }
    val iconCache = remember { mutableMapOf<Pair<Int, Int>, BitmapDescriptor>() }
    fun getCachedIcon(resId: Int, sizePx: Int = targetMarkerWidthPx): BitmapDescriptor {
        val key = resId to sizePx
        return iconCache.getOrPut(key) { getResizedIcon(context, resId, sizePx) }
    }
    fun runMarkerBounce(pin: HeritagePin, marker: Marker) {
        val iconRes = when (pin.type) {
            HeritageType.ANCIENT_BUILDING -> R.drawable.ic_pin_building_default
            HeritageType.GROTTO -> R.drawable.ic_pin_grotto_default
            HeritageType.TOWER -> R.drawable.ic_pic_tower_default
        }
        marker.setIcon(getCachedIcon(iconRes, targetMarkerWidthLargePx))
        longPressTracker.handler.postDelayed({
            marker.setIcon(getCachedIcon(iconRes, targetMarkerWidthPx))
        }, 180)
        longPressTracker.lastLongPressAt = SystemClock.uptimeMillis()
        longPressTracker.handler.postDelayed({
            onMarkerLongClick(pin)
        }, 320)
    }

    // 只有在 pins 发生变化时才更新 Marker，避免地图全量刷新
    LaunchedEffect(pins) {
        val aMap = mapView.map
        val currentIds = pins.mapTo(mutableSetOf()) { it.id }

        // Remove markers that no longer exist.
        val toRemove = markersById.keys.filter { it !in currentIds }
        toRemove.forEach { id ->
            markersById.remove(id)?.remove()
        }

        // Add/update markers without clearing the whole map.
        pins.forEach { pin ->
            val iconRes = when (pin.type) {
                HeritageType.ANCIENT_BUILDING -> R.drawable.ic_pin_building_default
                HeritageType.GROTTO -> R.drawable.ic_pin_grotto_default
                HeritageType.TOWER -> R.drawable.ic_pic_tower_default
            }
            val marker = markersById[pin.id]
            if (marker == null) {
                val newMarker = aMap.addMarker(
                    MarkerOptions()
                        .position(LatLng(pin.latitude, pin.longitude))
                        .title(pin.name)
                        .icon(getCachedIcon(iconRes))
                )
                if (newMarker != null) {
                    markersById[pin.id] = newMarker
                }
            } else {
                marker.position = LatLng(pin.latitude, pin.longitude)
                marker.title = pin.name
                marker.setIcon(getCachedIcon(iconRes))
            }
        }
    }

    // 聚焦指定 pin（来自列表双击）
    LaunchedEffect(focusPinId, pins) {
        if (focusPinId == null) return@LaunchedEffect
        val marker = markersById[focusPinId]
        if (marker != null) {
            val pos = marker.position
            mapView.map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 16f))
            marker.showInfoWindow()
            onFocusHandled()
            return@LaunchedEffect
        }
        val pin = latestPins.find { it.id == focusPinId }
        if (pin != null) {
            val latLng = LatLng(pin.latitude, pin.longitude)
            mapView.map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
            onFocusHandled()
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                val aMap = this.map
                aMap.setOnMapLoadedListener {
                    val target = aMap.cameraPosition.target
                    onDebugUpdate("debug: mapLoaded=true center=${target.latitude},${target.longitude} zoom=${aMap.cameraPosition.zoom}")
                }
                aMap.setOnMyLocationChangeListener { location ->
                    if (location != null) {
                        onDebugUpdate("debug: loc=${location.latitude},${location.longitude} acc=${location.accuracy} provider=${location.provider}")
                    } else {
                        onDebugUpdate("debug: loc=null")
                    }
                }
                val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
                val touchThresholdPx = (96 * context.resources.displayMetrics.density).toInt()
                fun findMarkerNear(x: Float, y: Float): Marker? {
                    val touchPoint = Point(x.toInt(), y.toInt())
                    var best: Marker? = null
                    var bestDist = touchThresholdPx * touchThresholdPx + 1
                    markersById.values.forEach { marker ->
                        val p = aMap.projection.toScreenLocation(marker.position)
                        val dx = p.x - touchPoint.x
                        val dy = p.y - touchPoint.y
                        val dist = dx * dx + dy * dy
                        if (dist <= touchThresholdPx * touchThresholdPx && dist < bestDist) {
                            best = marker
                            bestDist = dist
                        }
                    }
                    if (best != null) {
                        onDebugUpdate("debug: nearest marker dist=${kotlin.math.sqrt(bestDist.toDouble()).toInt()}px")
                    }
                    return best
                }

                aMap.setOnMapLongClickListener { latLng ->
                    // 如果长按点附近有 pin，则当作长按 pin
                    val near = markersById.entries.minByOrNull { entry ->
                        AMapUtils.calculateLineDistance(entry.value.position, latLng)
                    }
                    val nearEntry = near
                    val nearDist = if (nearEntry != null) {
                        AMapUtils.calculateLineDistance(nearEntry.value.position, latLng)
                    } else {
                        Float.MAX_VALUE
                    }
                    if (nearEntry != null && nearDist <= 200f) {
                        val pin = latestPins.find { it.id == nearEntry.key }
                        if (pin != null) {
                            runMarkerBounce(pin, nearEntry.value)
                            return@setOnMapLongClickListener
                        }
                    }
                    onMapLongClickState(latLng)
                }
                aMap.setOnMarkerClickListener { marker ->
                    val now = SystemClock.uptimeMillis()
                    if (now - longPressTracker.lastLongPressAt < 400) {
                        return@setOnMarkerClickListener true
                    }
                    val pinId = markersById.entries.firstOrNull { it.value == marker }?.key
                    val pin = latestPins.find { it.id == pinId }
                    if (pin != null) {
                        onMarkerClickState(pin, marker.position)
                    }
                    true
                }
                mapView.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            onDebugUpdate("debug: touch down x=${event.x.toInt()} y=${event.y.toInt()}")
                            longPressTracker.downX = event.x
                            longPressTracker.downY = event.y
                            longPressTracker.fired = false
                            longPressTracker.candidateMarker = findMarkerNear(event.x, event.y)
                            longPressTracker.cancelPending()
                            val candidate = longPressTracker.candidateMarker
                            if (candidate != null) {
                                onDebugUpdate("debug: candidate marker found")
                                val runnable = Runnable {
                                    if (longPressTracker.candidateMarker != candidate || longPressTracker.fired) return@Runnable
                                    longPressTracker.fired = true
                                    val pinId = markersById.entries.firstOrNull { it.value == candidate }?.key
                                    val pin = latestPins.find { it.id == pinId }
                                    if (pin != null) {
                                        runMarkerBounce(pin, candidate)
                                    }
                                }
                                longPressTracker.runnable = runnable
                                longPressTracker.handler.postDelayed(runnable, longPressTimeout)
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.x - longPressTracker.downX
                            val dy = event.y - longPressTracker.downY
                            if (dx * dx + dy * dy > touchSlop * touchSlop) {
                                longPressTracker.cancelPending()
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            longPressTracker.cancelPending()
                        }
                    }
                    false
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            val aMap = view.map
            // 仅在必要时更新属性
            if (aMap.mapType != (if (isSatellite) AMap.MAP_TYPE_SATELLITE else AMap.MAP_TYPE_NORMAL)) {
                aMap.mapType = if (isSatellite) AMap.MAP_TYPE_SATELLITE else AMap.MAP_TYPE_NORMAL
            }
            aMap.uiSettings.isScaleControlsEnabled = true

            if (hasLocationPermission && !aMap.isMyLocationEnabled) {
                val myLocationStyle = MyLocationStyle()
                myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATE)
                aMap.myLocationStyle = myLocationStyle
                aMap.isMyLocationEnabled = true
                aMap.uiSettings.isMyLocationButtonEnabled = true
            }
        }
    )
}

// 辅助函数：根据比例缩放图标，确保不失真且透明
fun getResizedIcon(context: Context, resId: Int, targetWidthPx: Int): BitmapDescriptor {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = false }
    val image = BitmapFactory.decodeResource(context.resources, resId, options)
    val targetHeightPx = (targetWidthPx * image.height) / image.width
    val resized = Bitmap.createScaledBitmap(image, targetWidthPx, targetHeightPx, true)
    return BitmapDescriptorFactory.fromBitmap(resized)
}

private class LongPressTracker(val handler: Handler) {
    var downX: Float = 0f
    var downY: Float = 0f
    var candidateMarker: Marker? = null
    var runnable: Runnable? = null
    var fired: Boolean = false
    var lastLongPressAt: Long = 0L

    fun cancelPending() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        candidateMarker = null
        fired = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPinBottomSheetContent(
    initialPin: HeritagePin?,
    onSave: (HeritagePin) -> Unit,
    onDelete: (HeritagePin) -> Unit,
    onCancelDelete: (HeritagePin) -> Unit,
    onCancelCreate: () -> Unit
) {
    var name by remember { mutableStateOf(initialPin?.name ?: "") }
    var description by remember { mutableStateOf(initialPin?.description ?: "") }
    var selectedType by remember { mutableStateOf(initialPin?.type ?: HeritageType.TOWER) }
    var selectedLevel by remember { mutableStateOf(initialPin?.level ?: HeritageLevel.DEFAULT) }
    var imagePath by remember { mutableStateOf(initialPin?.imagePath) }
    var isConservator by remember { mutableStateOf(false) }
    var showImagePreview by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { scope.launch { imagePath = saveImageToInternalStorage(context, it) } }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState())) {
        if (initialPin != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { onCancelDelete(initialPin) }) {
                    Text("取消", color = Color.Red)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(if (initialPin == null) "新发现" else "编辑遗迹", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFFF2F2F7)),
                contentAlignment = Alignment.CenterStart
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 16.sp),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        if (name.isEmpty()) Text("标题", color = Color.Gray)
                        innerTextField()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("等级", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.padding(vertical = 12.dp)) {
            HeritageLevel.entries.forEach { level ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp).clickable { selectedLevel = level }) {
                    RadioButton(selected = selectedLevel == level, onClick = { selectedLevel = level }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF5856D6)))
                    Text(when(level){ HeritageLevel.DEFAULT->"默认"; HeritageLevel.PROVINCIAL->"省保"; HeritageLevel.NATIONAL->"国保"; HeritageLevel.WORLD->"世遗" }, fontSize = 14.sp)
                }
            }
        }

        Text("估计类型", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.padding(vertical = 12.dp)) {
            HeritageType.entries.forEach { type ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp).clickable { selectedType = type }) {
                    RadioButton(selected = selectedType == type, onClick = { selectedType = type }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF5856D6)))
                    Text(when(type){ HeritageType.TOWER->"塔"; HeritageType.GROTTO->"石刻"; HeritageType.ANCIENT_BUILDING->"古建" }, fontSize = 14.sp)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("描述", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.width(8.dp))
            Checkbox(
                checked = isConservator,
                onCheckedChange = { isConservator = it }
            )
            Text("含文保人员 每一个文保人员都值得我们记得", fontSize = 14.sp, color = Color.Gray)
        }
        Box(
            modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth().height(100.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFF2F2F7))
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.padding(16.dp).fillMaxSize(),
                textStyle = TextStyle(fontSize = 15.sp),
                decorationBox = { innerTextField ->
                    if (description.isEmpty()) Text("请输入描述", color = Color.Gray)
                    innerTextField()
                }
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFFE5E5EA)).clickable { imagePicker.launch("image/*") }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加照片", fontSize = 14.sp)
                }
            }
            if (imagePath != null) {
                Spacer(Modifier.width(16.dp))
                AsyncImage(
                    model = File(imagePath!!),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showImagePreview = true },
                    contentScale = ContentScale.Crop
                )
            }
        }

        if (showImagePreview && imagePath != null) {
            Dialog(onDismissRequest = { showImagePreview = false }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                ) {
                    AsyncImage(
                        model = File(imagePath!!),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showImagePreview = false },
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (initialPin == null) {
                OutlinedButton(
                    onClick = { onCancelCreate() },
                    modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(27.dp)
                ) { Text("取消", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
            Button(
                onClick = { if (name.isNotBlank()) onSave(initialPin?.copy(name = name, type = selectedType, level = selectedLevel, description = description, imagePath = imagePath) ?: HeritagePin(name = name, type = selectedType, level = selectedLevel, latitude = 0.0, longitude = 0.0, description = description, imagePath = imagePath)) },
                modifier = Modifier.weight(1f).height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5856D6)),
                shape = RoundedCornerShape(27.dp)
            ) { Text("保存", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }

        if (initialPin != null) {
            TextButton(onClick = { onDelete(initialPin) }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
                Text("删除此记录", color = Color.Red)
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    heritageDao: HeritageDao,
    onShowOnMap: (HeritagePin) -> Unit
) {
    val pins by heritageDao.getAllPins().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editingPin by remember { mutableStateOf<HeritagePin?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("访古足迹", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn {
            items(pins) { pin ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .pointerInput(pin.id) {
                            detectTapGestures(
                                onTap = { editingPin = pin },
                                onDoubleTap = { onShowOnMap(pin) }
                            )
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F2F7))
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (pin.imagePath != null) {
                            AsyncImage(model = File(pin.imagePath), contentDescription = null, modifier = Modifier.size(70.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        } else {
                            Box(modifier = Modifier.size(70.dp).background(Color.LightGray, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Image, contentDescription = null, tint = Color.White) }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pin.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(when(pin.level){ HeritageLevel.DEFAULT->"默认"; HeritageLevel.PROVINCIAL->"省保"; HeritageLevel.NATIONAL->"国保"; HeritageLevel.WORLD->"世遗" }, color = Color(0xFF5856D6), fontSize = 12.sp)
                            Text(pin.description, maxLines = 1, color = Color.Gray, fontSize = 14.sp)
                        }
                        IconButton(onClick = { scope.launch { heritageDao.deletePin(pin) } }) { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.LightGray) }
                    }
                }
            }
        }
    }
    
    // 列表页点击也可弹窗编辑
    if (editingPin != null) {
        ModalBottomSheet(onDismissRequest = { editingPin = null }) {
            AddPinBottomSheetContent(
                initialPin = editingPin,
                onSave = {
                    scope.launch {
                        heritageDao.updatePin(it.copy(updatedAt = System.currentTimeMillis()))
                        editingPin = null
                    }
                },
                onDelete = { scope.launch { heritageDao.deletePin(it); editingPin = null } },
                onCancelDelete = { scope.launch { heritageDao.deletePin(it); editingPin = null } },
                onCancelCreate = { editingPin = null }
            )
        }
    }
}

suspend fun saveImageToInternalStorage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val file = File(context.filesDir, "heritage_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { output -> inputStream?.use { it.copyTo(output) } }
        file.absolutePath
    } catch (e: Exception) { e.printStackTrace(); null }
}

@Composable
fun MapLifecycleHandler(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

enum class AppDestinations(val label: String, val icon: ImageVector) {
    MAP("地图", Icons.Default.Map),
    LIST("足迹", Icons.Default.CollectionsBookmark),
}
