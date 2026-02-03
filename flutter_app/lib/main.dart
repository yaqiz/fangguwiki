import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'dart:convert';
import 'dart:ui' as ui;

import 'package:amap_map/amap_map.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:sqflite/sqflite.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:x_amap_base/x_amap_base.dart';
import 'package:http/http.dart' as http;
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const FangGuApp());
}

class FangGuApp extends StatefulWidget {
  const FangGuApp({super.key});

  @override
  State<FangGuApp> createState() => _FangGuAppState();
}

class _FangGuAppState extends State<FangGuApp> {
  final PinStore _store = PinStore();

  @override
  void initState() {
    super.initState();
    _store.init();
  }

  @override
  Widget build(BuildContext context) {
    // TODO: replace with your AMap keys
    final AMapApiKey apiKeys = AMapApiKey(
      androidKey: '454f199270a6389caa1fee98f974aedf',
      iosKey: 'YOUR_IOS_AMAP_KEY',
    );
    AMapInitializer.init(context, apiKey: apiKeys);
    AMapInitializer.updatePrivacyAgree(
      const AMapPrivacyStatement(hasAgree: true, hasShow: true, hasContains: true),
    );

    return MaterialApp(
      title: 'FangGu',
      theme: ThemeData(useMaterial3: true),
      home: HomePage(store: _store),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.store});
  final PinStore store;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  int _index = 0;
  final ValueNotifier<HeritagePin?> _focusPin = ValueNotifier(null);

  @override
  Widget build(BuildContext context) {
    final pages = [
      MapPage(store: widget.store, focusPin: _focusPin),
      ListPage(
        store: widget.store,
        onFocusPin: (pin) => setState(() {
          _index = 0;
          _focusPin.value = pin;
        }),
      ),
    ];

    return Scaffold(
      body: pages[_index],
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.map), label: '地图'),
          NavigationDestination(icon: Icon(Icons.collections_bookmark), label: '足迹'),
        ],
      ),
    );
  }
}

class MapPage extends StatefulWidget {
  const MapPage({super.key, required this.store, required this.focusPin});
  final PinStore store;
  final ValueNotifier<HeritagePin?> focusPin;

  @override
  State<MapPage> createState() => _MapPageState();
}

class _MapPageState extends State<MapPage> {
  AMapController? _controller;
  AMapLocation? _lastLocation;
  String _searchQuery = '';
  HeritagePin? _menuTarget;
  HeritagePin? _editingPin;
  LatLng? _selectedLatLng;
  Marker? _tempMarker;
  bool _satellite = false;
  static const String _amapWebKey = 'bd34f7f79df2965c4c9d57c2511a5de7';
  final http.Client _httpClient = http.Client();
  final Map<String, LatLng> _searchCache = {};
  String _debugEvent = 'init';
  LatLng? _pendingFocus;
  final Map<HeritageType, BitmapDescriptor> _iconCache = {};
  bool _iconsReady = false;
  final Map<int, Marker> _markerCache = {};

  @override
  void initState() {
    super.initState();
    _requestLocationPermission();
    widget.focusPin.addListener(_handleExternalFocus);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _preloadIcons();
    });
  }

  @override
  void dispose() {
    widget.focusPin.removeListener(_handleExternalFocus);
    super.dispose();
  }

  void _handleExternalFocus() {
    final pin = widget.focusPin.value;
    if (pin == null) return;
    final target = LatLng(pin.latitude, pin.longitude);
    if (_controller == null) {
      _pendingFocus = target;
    } else {
      _controller?.moveCamera(CameraUpdate.newLatLngZoom(target, 14));
    }
    widget.focusPin.value = null;
  }

  Future<void> _preloadIcons() async {
    try {
      const markerWidth = 72;
      final towerBytes = await _loadMarkerBytes('assets/icons/tower.png', markerWidth);
      final grottoBytes = await _loadMarkerBytes('assets/icons/grotto.png', markerWidth);
      final buildingBytes = await _loadMarkerBytes('assets/icons/building.png', markerWidth);
      final tower = BitmapDescriptor.fromBytes(towerBytes);
      final grotto = BitmapDescriptor.fromBytes(grottoBytes);
      final building = BitmapDescriptor.fromBytes(buildingBytes);
      _iconCache[HeritageType.tower] = tower;
      _iconCache[HeritageType.grotto] = grotto;
      _iconCache[HeritageType.ancientBuilding] = building;
      _iconsReady = true;
      if (mounted) {
        setState(() => _debugEvent = 'icons loaded');
      }
    } catch (e) {
      if (mounted) {
        setState(() => _debugEvent = 'icon load failed');
      }
    }
  }

  Future<Uint8List> _loadMarkerBytes(String asset, int targetWidth) async {
    final data = await rootBundle.load(asset);
    final codec = await ui.instantiateImageCodec(
      data.buffer.asUint8List(),
      targetWidth: targetWidth,
    );
    final frame = await codec.getNextFrame();
    final bytes = await frame.image.toByteData(format: ui.ImageByteFormat.png);
    return bytes!.buffer.asUint8List();
  }

  Future<void> _requestLocationPermission() async {
    await Permission.location.request();
  }

  Future<void> _searchAndMove(String query) async {
    final keywords = Uri.encodeComponent(query.trim());
    if (keywords.isEmpty) return;
    final cached = _searchCache[keywords];
    if (cached != null) {
        _controller?.moveCamera(
          CameraUpdate.newLatLngZoom(cached, 14),
        );
        return;
    }
    final uri = Uri.parse(
      'https://restapi.amap.com/v3/place/text?keywords=$keywords&key=$_amapWebKey&offset=1&page=1&extensions=base',
    );
    try {
      _showSnack('搜索中...');
      final res = await _httpClient.get(uri).timeout(const Duration(seconds: 6));
      if (res.statusCode != 200) {
        _showSnack('搜索失败(${res.statusCode})');
        return;
      }
      final json = jsonDecode(res.body) as Map<String, dynamic>;
      final status = json['status']?.toString();
      if (status != '1') {
        _showSnack('搜索失败(${json['info'] ?? 'unknown'})');
        return;
      }
      final pois = json['pois'] as List<dynamic>;
      if (pois.isEmpty) {
        _showSnack('未找到相关地点');
        return;
      }
      final loc = (pois.first as Map<String, dynamic>)['location']?.toString();
      if (loc == null || !loc.contains(',')) {
        _showSnack('解析位置失败');
        return;
      }
      final parts = loc.split(',');
      final lon = double.tryParse(parts[0]);
      final lat = double.tryParse(parts[1]);
      if (lat == null || lon == null) {
        _showSnack('解析位置失败');
        return;
      }
      final target = LatLng(lat, lon);
      _searchCache[keywords] = target;
      _controller?.moveCamera(
        CameraUpdate.newLatLngZoom(target, 14),
      );
    } catch (e) {
      _showSnack('搜索异常');
    }
  }

  void _showSnack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg)),
    );
  }

  CameraPosition get _initialPosition => const CameraPosition(
        target: LatLng(39.909187, 116.397451),
        zoom: 10,
      );

  Set<Marker> _buildMarkers(List<HeritagePin> pins) {
    final existingIds = _markerCache.keys.toSet();
    final incomingIds = pins.map((p) => p.id ?? -1).toSet();

    // remove missing
    for (final id in existingIds.difference(incomingIds)) {
      _markerCache.remove(id);
    }

    for (final pin in pins) {
      final id = pin.id ?? -1;
      final pos = LatLng(pin.latitude, pin.longitude);
      final icon = _iconForType(pin.type);
      final info = InfoWindow(title: pin.name, snippet: pin.description);
      final existing = _markerCache[id];
      if (existing == null) {
        _markerCache[id] = Marker(
          position: pos,
          icon: icon,
          infoWindow: info,
          visible: true,
          alpha: 1.0,
        );
      } else {
        _markerCache[id] = existing.copyWith(
          positionParam: pos,
          iconParam: icon,
          infoWindowParam: info,
          visibleParam: true,
          alphaParam: 1.0,
        );
      }
    }

    return _markerCache.values.toSet();
  }

  Future<void> _openEditSheet(BuildContext context, HeritagePin? pin) async {
    final result = await showModalBottomSheet<PinEditResult>(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => PinEditSheet(initialPin: pin),
    );

    if (result == null) return;
    if (result.action == PinEditAction.cancelCreate) {
      if (_tempMarker != null) {
        setState(() => _tempMarker = null);
      }
      return;
    }
    if (result.pin == null) return;

    if (result.action == PinEditAction.save) {
      final now = DateTime.now().millisecondsSinceEpoch;
      if (pin == null) {
        final latLng = _selectedLatLng;
        if (latLng == null) return;
        await widget.store.insertPin(
          result.pin!.copyWith(
            latitude: latLng.latitude,
            longitude: latLng.longitude,
            createdAt: now,
            updatedAt: now,
          ),
        );
      } else {
        await widget.store.updatePin(result.pin!.copyWith(updatedAt: now));
      }
    } else if (result.action == PinEditAction.delete && pin != null) {
      await widget.store.deletePin(pin);
    }

    if (_tempMarker != null) {
      setState(() => _tempMarker = null);
    }
  }

  void _handleLongPress(LatLng latLng, List<HeritagePin> pins) {
    final near = _findNearestPin(latLng, pins);
    if (near != null && near.distanceMeters <= 200) {
      setState(() => _debugEvent = 'long-press near pin=${near.pin.id}');
      setState(() => _menuTarget = near.pin);
      return;
    }

    // temp marker animation (simple)
    setState(() => _debugEvent = 'long-press new pin');
    setState(() {
      _tempMarker = Marker(
        position: latLng,
        icon: _iconForType(HeritageType.tower),
        visible: true,
        alpha: 1.0,
        zIndex: 2.0,
      );
    });
    Future.delayed(const Duration(milliseconds: 500), () {
      _selectedLatLng = latLng;
      _openEditSheet(context, null);
    });
  }

  BitmapDescriptor _iconForType(HeritageType type) {
    if (!_iconsReady) {
      return BitmapDescriptor.defaultMarkerWithHue(BitmapDescriptor.hueAzure);
    }
    switch (type) {
      case HeritageType.tower:
        return _iconCache[type] ?? BitmapDescriptor.fromIconPath('assets/icons/tower.png');
      case HeritageType.grotto:
        return _iconCache[type] ?? BitmapDescriptor.fromIconPath('assets/icons/grotto.png');
      case HeritageType.ancientBuilding:
        return _iconCache[type] ?? BitmapDescriptor.fromIconPath('assets/icons/building.png');
    }
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<List<HeritagePin>>(
      valueListenable: widget.store.pins,
      builder: (context, pins, _) {
        final markers = _buildMarkers(pins);
        if (_tempMarker != null) {
          markers.add(_tempMarker!);
        }
        final firstPin = pins.isNotEmpty ? pins.first : null;
        final firstCoord = firstPin == null
            ? 'none'
            : '${firstPin.latitude.toStringAsFixed(5)},${firstPin.longitude.toStringAsFixed(5)}';

        return Stack(
          children: [
            AMapWidget(
              initialCameraPosition: _initialPosition,
              mapType: _satellite ? MapType.satellite : MapType.normal,
              scaleEnabled: true,
              onMapCreated: (controller) {
                _controller = controller;
                if (_pendingFocus != null) {
                  final target = _pendingFocus!;
                  _pendingFocus = null;
                  _controller?.moveCamera(CameraUpdate.newLatLngZoom(target, 14));
                }
              },
              onTap: (_) => setState(() => _debugEvent = 'tap map'),
              onLongPress: (latLng) => _handleLongPress(latLng, pins),
              onLocationChanged: (loc) => _lastLocation = loc,
              myLocationStyleOptions: MyLocationStyleOptions(true),
              markers: markers,
            ),
            Positioned(
              top: 48,
              left: 16,
              right: 16,
              child: _SearchBar(
                value: _searchQuery,
                onChanged: (v) => setState(() => _searchQuery = v),
                onSearch: () {
                  _searchAndMove(_searchQuery);
                },
                onLocate: () {
                  final loc = _lastLocation;
                  if (loc == null) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('正在定位中，请稍后再试')),
                    );
                    return;
                  }
                  _controller?.moveCamera(
                    CameraUpdate.newLatLngZoom(LatLng(loc.latLng.latitude, loc.latLng.longitude), 14),
                  );
                },
              ),
            ),
            Positioned(
              right: 16,
              top: MediaQuery.of(context).size.height / 2 - 24,
              child: FloatingActionButton(
                onPressed: () => setState(() => _satellite = !_satellite),
                backgroundColor: Colors.white,
                child: const Icon(Icons.layers, color: Colors.black87),
              ),
            ),
            Positioned(
              top: 8,
              left: 8,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
                decoration: BoxDecoration(
                  color: Colors.black.withOpacity(0.6),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  'pins: ${pins.length}  markers: ${markers.length}\\nfirst: $firstCoord\\nicons: $_iconsReady  temp: ${_tempMarker != null}\\nlast: $_debugEvent',
                  style: const TextStyle(color: Colors.white, fontSize: 11),
                ),
              ),
            ),
            if (_menuTarget != null)
              _PinMenu(
                pin: _menuTarget!,
                onClose: () => setState(() => _menuTarget = null),
                onEdit: () {
                  final pin = _menuTarget;
                  setState(() => _menuTarget = null);
                  if (pin == null) return;
                  _editingPin = pin;
                  _selectedLatLng = LatLng(pin.latitude, pin.longitude);
                  _openEditSheet(context, pin);
                },
                onDelete: () async {
                  final pin = _menuTarget;
                  setState(() => _menuTarget = null);
                  if (pin == null) return;
                  final ok = await showDialog<bool>(
                    context: context,
                    builder: (ctx) => AlertDialog(
                      title: const Text('删除标记？'),
                      content: const Text('确定要删除这个地点吗？'),
                      actions: [
                        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
                        TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('删除')),
                      ],
                    ),
                  );
                  if (ok == true) {
                    await widget.store.deletePin(pin);
                  }
                },
                onNavigate: () => _openExternalNav(pin: _menuTarget!),
              ),
          ],
        );
      },
    );
  }

  Future<void> _openExternalNav({required HeritagePin pin}) async {
    final name = Uri.encodeComponent(pin.name);
    final lat = pin.latitude;
    final lon = pin.longitude;

    final List<Uri> candidates = [
      Uri.parse('androidamap://navi?sourceApplication=fanggu&poiname=$name&lat=$lat&lon=$lon&dev=0&style=2'),
      Uri.parse('amapuri://route/plan/?dlat=$lat&dlon=$lon&dname=$name&dev=0&t=0'),
      if (Platform.isIOS)
        Uri.parse('http://maps.apple.com/?q=$lat,$lon')
      else
        Uri.parse('geo:$lat,$lon?q=$lat,$lon($name)'),
      Uri.parse('https://maps.google.com/?q=$lat,$lon'),
    ];

    for (final uri in candidates) {
      if (await canLaunchUrl(uri)) {
        await launchUrl(uri, mode: LaunchMode.externalApplication);
        return;
      }
    }

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('未安装地图应用')),
      );
    }
  }
}

class _SearchBar extends StatelessWidget {
  const _SearchBar({
    required this.value,
    required this.onChanged,
    required this.onSearch,
    required this.onLocate,
  });

  final String value;
  final ValueChanged<String> onChanged;
  final VoidCallback onSearch;
  final VoidCallback onLocate;

  @override
  Widget build(BuildContext context) {
    return Material(
      elevation: 4,
      borderRadius: BorderRadius.circular(27),
      child: Container(
        height: 54,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(27)),
        child: Row(
          children: [
            const Icon(Icons.arrow_back, color: Colors.grey),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                decoration: const InputDecoration(
                  hintText: '查找地点、公交、地铁',
                  border: InputBorder.none,
                ),
                onChanged: onChanged,
              ),
            ),
            if (value.isNotEmpty)
              IconButton(
                icon: const Icon(Icons.close, size: 18),
                onPressed: () => onChanged(''),
              ),
            const VerticalDivider(width: 1),
            TextButton(onPressed: onSearch, child: const Text('搜索')),
            const SizedBox(width: 6),
            TextButton(onPressed: onLocate, child: const Text('当前地点')),
          ],
        ),
      ),
    );
  }
}

class ListPage extends StatelessWidget {
  const ListPage({super.key, required this.store, required this.onFocusPin});
  final PinStore store;
  final ValueChanged<HeritagePin> onFocusPin;

  Future<void> _openEditSheet(BuildContext context, HeritagePin pin) async {
    final result = await showModalBottomSheet<PinEditResult>(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => PinEditSheet(initialPin: pin),
    );
    if (result == null) return;
    if (result.action == PinEditAction.save && result.pin != null) {
      final now = DateTime.now().millisecondsSinceEpoch;
      await store.updatePin(result.pin!.copyWith(updatedAt: now));
    } else if (result.action == PinEditAction.delete) {
      await store.deletePin(pin);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<List<HeritagePin>>(
      valueListenable: store.pins,
      builder: (context, pins, _) {
        return Scaffold(
          backgroundColor: const Color(0xFFE8F2F8),
          appBar: AppBar(
            title: const Text('访古足迹'),
            backgroundColor: Colors.transparent,
            elevation: 0,
            foregroundColor: Colors.black87,
          ),
          body: ListView.builder(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
            itemCount: pins.length,
            itemBuilder: (context, index) {
              final pin = pins[index];
              return Container(
                margin: const EdgeInsets.symmetric(vertical: 6),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(14),
                  boxShadow: const [
                    BoxShadow(
                      color: Color(0x11000000),
                      blurRadius: 8,
                      offset: Offset(0, 4),
                    ),
                  ],
                ),
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => _openEditSheet(context, pin),
                  onDoubleTap: () => onFocusPin(pin),
                  child: Row(
                    children: [
                      _PinTypeIcon(type: pin.type),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              pin.name,
                              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              '${pin.type.label} · ${pin.level.label}',
                              style: const TextStyle(fontSize: 12, color: Color(0xFF6B7280)),
                            ),
                          ],
                        ),
                      ),
                      IconButton(
                        icon: const Icon(Icons.delete_outline),
                        onPressed: () => store.deletePin(pin),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        );
      },
    );
  }
}

class _PinTypeIcon extends StatelessWidget {
  const _PinTypeIcon({required this.type});
  final HeritageType type;

  @override
  Widget build(BuildContext context) {
    String asset;
    switch (type) {
      case HeritageType.tower:
        asset = 'assets/icons/tower.png';
        break;
      case HeritageType.grotto:
        asset = 'assets/icons/grotto.png';
        break;
      case HeritageType.ancientBuilding:
        asset = 'assets/icons/building.png';
        break;
    }
    return Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        color: const Color(0xFFEFF6FB),
        borderRadius: BorderRadius.circular(10),
      ),
      alignment: Alignment.center,
      child: Image.asset(asset, width: 28, height: 28),
    );
  }
}

class PinEditSheet extends StatefulWidget {
  const PinEditSheet({super.key, this.initialPin});
  final HeritagePin? initialPin;

  @override
  State<PinEditSheet> createState() => _PinEditSheetState();
}

class _PinEditSheetState extends State<PinEditSheet> {
  late TextEditingController _name;
  late TextEditingController _desc;
  HeritageType _type = HeritageType.tower;
  HeritageLevel _level = HeritageLevel.defaultLevel;
  String? _imagePath;
  bool _showPreview = false;
  bool _isConservator = false;

  @override
  void initState() {
    super.initState();
    final pin = widget.initialPin;
    _name = TextEditingController(text: pin?.name ?? '');
    _desc = TextEditingController(text: pin?.description ?? '');
    _type = pin?.type ?? HeritageType.tower;
    _level = pin?.level ?? HeritageLevel.defaultLevel;
    _imagePath = pin?.imagePath;
    _isConservator = false;
  }

  @override
  Widget build(BuildContext context) {
    final isEditing = widget.initialPin != null;
    return Padding(
      padding: EdgeInsets.only(
        left: 24,
        right: 24,
        top: 16,
        bottom: 90 +
            MediaQuery.of(context).viewInsets.bottom +
            MediaQuery.of(context).padding.bottom,
      ),
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (isEditing)
              Align(
                alignment: Alignment.centerRight,
                child: TextButton(
                  onPressed: () => Navigator.pop(
                    context,
                    PinEditResult(action: PinEditAction.delete, pin: widget.initialPin),
                  ),
                  child: const Text('取消', style: TextStyle(color: Colors.red, fontSize: 11)),
                ),
              ),
            Row(
              children: [
                Text(isEditing ? '编辑遗迹' : '新发现', style: const TextStyle(fontSize: 14, fontWeight: FontWeight.bold)),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    controller: _name,
                    style: const TextStyle(fontSize: 12),
                    decoration: const InputDecoration(hintText: '标题'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                const Text('等级', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 11)),
                const SizedBox(width: 10),
                Expanded(
                  child: Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: HeritageLevel.values.where((l) => l != HeritageLevel.world).map((level) {
                      return ChoiceChip(
                        label: Text(level.label, style: const TextStyle(fontSize: 10)),
                        selected: _level == level,
                        onSelected: (_) => setState(() => _level = level),
                      );
                    }).toList(),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                const Text('类型', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 11)),
                const SizedBox(width: 10),
                Expanded(
                  child: Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: HeritageType.values.map((type) {
                      return ChoiceChip(
                        label: Text(type.label, style: const TextStyle(fontSize: 10)),
                        selected: _type == type,
                        onSelected: (_) => setState(() => _type = type),
                      );
                    }).toList(),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                const Text('描述', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 11)),
                const SizedBox(width: 8),
                Checkbox(
                  value: _isConservator,
                  onChanged: (v) => setState(() => _isConservator = v ?? false),
                ),
                const Text('含文保人员', style: TextStyle(color: Colors.grey, fontSize: 10)),
              ],
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _desc,
              maxLines: 2,
              style: const TextStyle(fontSize: 11),
              decoration: const InputDecoration(hintText: '请输入描述'),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                OutlinedButton.icon(
                  onPressed: _pickImage,
                  icon: const Icon(Icons.photo_camera),
                  label: const Text('添加照片', style: TextStyle(fontSize: 11)),
                ),
                if (_imagePath != null) ...[
                  const SizedBox(width: 12),
                  GestureDetector(
                    onTap: () => setState(() => _showPreview = true),
                    child: Image.file(
                      File(_imagePath!),
                      width: 48,
                      height: 48,
                      fit: BoxFit.cover,
                    ),
                  ),
                ],
              ],
            ),
            if (_showPreview && _imagePath != null)
              Dialog(
                child: GestureDetector(
                  onTap: () => setState(() => _showPreview = false),
                  child: Image.file(File(_imagePath!), fit: BoxFit.contain),
                ),
              ),
            const SizedBox(height: 24),
            Row(
              children: [
                if (!isEditing)
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () => Navigator.pop(
                        context,
                        const PinEditResult(action: PinEditAction.cancelCreate),
                      ),
                      child: const Text('取消', style: TextStyle(fontSize: 13)),
                    ),
                  ),
                if (!isEditing) const SizedBox(width: 12),
                Expanded(
                  child: FilledButton(
                    onPressed: () {
                      final pin = HeritagePin(
                        id: widget.initialPin?.id,
                        name: _name.text.trim(),
                        type: _type,
                        level: _level,
                        latitude: widget.initialPin?.latitude ?? 0,
                        longitude: widget.initialPin?.longitude ?? 0,
                        description: _desc.text.trim(),
                        imagePath: _imagePath,
                        createdAt: widget.initialPin?.createdAt,
                        updatedAt: widget.initialPin?.updatedAt,
                      );
                      Navigator.pop(context, PinEditResult(action: PinEditAction.save, pin: pin));
                    },
                    child: const Text('保存', style: TextStyle(fontSize: 13)),
                  ),
                ),
              ],
            ),
            if (isEditing)
              Center(
                child: TextButton(
                  onPressed: () => Navigator.pop(
                    context,
                    PinEditResult(action: PinEditAction.delete, pin: widget.initialPin),
                  ),
                  child: const Text('删除此记录', style: TextStyle(color: Colors.red, fontSize: 11)),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _pickImage() async {
    final picker = ImagePicker();
    final file = await picker.pickImage(source: ImageSource.gallery);
    if (file == null) return;
    final dir = await getApplicationDocumentsDirectory();
    final target = p.join(dir.path, 'heritage_${DateTime.now().millisecondsSinceEpoch}.jpg');
    await file.saveTo(target);
    setState(() => _imagePath = target);
  }
}

class _PinMenu extends StatelessWidget {
  const _PinMenu({
    required this.pin,
    required this.onClose,
    required this.onEdit,
    required this.onDelete,
    required this.onNavigate,
  });

  final HeritagePin pin;
  final VoidCallback onClose;
  final VoidCallback onEdit;
  final VoidCallback onDelete;
  final VoidCallback onNavigate;

  @override
  Widget build(BuildContext context) {
    return Positioned.fill(
      child: GestureDetector(
        onTap: onClose,
        child: Align(
          alignment: Alignment.bottomCenter,
          child: Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            decoration: const BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextButton(onPressed: onEdit, child: const Text('编辑')),
                TextButton(onPressed: onDelete, child: const Text('删除', style: TextStyle(color: Colors.red))),
                TextButton(onPressed: onNavigate, child: const Text('到那里去')),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class PinEditResult {
  const PinEditResult({required this.action, this.pin});
  final PinEditAction action;
  final HeritagePin? pin;
}

enum PinEditAction { save, delete, cancelCreate }

class HeritagePin {
  HeritagePin({
    this.id,
    required this.name,
    required this.type,
    required this.level,
    required this.latitude,
    required this.longitude,
    required this.description,
    this.imagePath,
    this.createdAt,
    this.updatedAt,
  });

  final int? id;
  final String name;
  final HeritageType type;
  final HeritageLevel level;
  final double latitude;
  final double longitude;
  final String description;
  final String? imagePath;
  final int? createdAt;
  final int? updatedAt;

  Map<String, Object?> toMap() => {
        'id': id,
        'name': name,
        'type': type.name,
        'level': level.name,
        'latitude': latitude,
        'longitude': longitude,
        'description': description,
        'imagePath': imagePath,
        'createdAt': createdAt,
        'updatedAt': updatedAt,
      };

  HeritagePin copyWith({
    int? id,
    String? name,
    HeritageType? type,
    HeritageLevel? level,
    double? latitude,
    double? longitude,
    String? description,
    String? imagePath,
    int? createdAt,
    int? updatedAt,
  }) {
    return HeritagePin(
      id: id ?? this.id,
      name: name ?? this.name,
      type: type ?? this.type,
      level: level ?? this.level,
      latitude: latitude ?? this.latitude,
      longitude: longitude ?? this.longitude,
      description: description ?? this.description,
      imagePath: imagePath ?? this.imagePath,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  static HeritagePin fromMap(Map<String, Object?> map) {
    return HeritagePin(
      id: map['id'] as int?,
      name: map['name'] as String,
      type: HeritageType.values.byName(map['type'] as String),
      level: HeritageLevel.values.byName(map['level'] as String),
      latitude: (map['latitude'] as num).toDouble(),
      longitude: (map['longitude'] as num).toDouble(),
      description: map['description'] as String,
      imagePath: map['imagePath'] as String?,
      createdAt: map['createdAt'] as int?,
      updatedAt: map['updatedAt'] as int?,
    );
  }
}

enum HeritageType { tower, grotto, ancientBuilding }

enum HeritageLevel { defaultLevel, provincial, national, world }

extension HeritageTypeLabel on HeritageType {
  String get label {
    switch (this) {
      case HeritageType.tower:
        return '塔';
      case HeritageType.grotto:
        return '石刻';
      case HeritageType.ancientBuilding:
        return '古建';
    }
  }
}

extension HeritageLevelLabel on HeritageLevel {
  String get label {
    switch (this) {
      case HeritageLevel.defaultLevel:
        return '默认';
      case HeritageLevel.provincial:
        return '省保';
      case HeritageLevel.national:
        return '国保';
      case HeritageLevel.world:
        return '世遗';
    }
  }
}

class PinStore {
  final ValueNotifier<List<HeritagePin>> pins = ValueNotifier<List<HeritagePin>>([]);
  Database? _db;

  Future<void> init() async {
    final dir = await getApplicationDocumentsDirectory();
    final path = p.join(dir.path, 'heritage.db');
    _db = await openDatabase(
      path,
      version: 1,
      onCreate: (db, _) async {
        await db.execute('''
          CREATE TABLE heritage_pins(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            type TEXT NOT NULL,
            level TEXT NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            description TEXT NOT NULL,
            imagePath TEXT,
            createdAt INTEGER,
            updatedAt INTEGER
          )
        ''');
      },
    );
    await reload();
    if (pins.value.isEmpty) {
      final now = DateTime.now().millisecondsSinceEpoch;
      await insertPin(
        HeritagePin(
          name: '测试点',
          type: HeritageType.tower,
          level: HeritageLevel.defaultLevel,
          latitude: 39.909187,
          longitude: 116.397451,
          description: '默认测试点',
          createdAt: now,
          updatedAt: now,
        ),
      );
    }
  }

  Future<void> reload() async {
    final db = _db;
    if (db == null) return;
    final rows = await db.query('heritage_pins', orderBy: 'updatedAt DESC');
    pins.value = rows.map(HeritagePin.fromMap).toList();
  }

  Future<void> insertPin(HeritagePin pin) async {
    final db = _db;
    if (db == null) return;
    await db.insert('heritage_pins', pin.toMap(), conflictAlgorithm: ConflictAlgorithm.replace);
    await reload();
  }

  Future<void> updatePin(HeritagePin pin) async {
    final db = _db;
    if (db == null) return;
    await db.update('heritage_pins', pin.toMap(), where: 'id = ?', whereArgs: [pin.id]);
    await reload();
  }

  Future<void> deletePin(HeritagePin pin) async {
    final db = _db;
    if (db == null) return;
    await db.delete('heritage_pins', where: 'id = ?', whereArgs: [pin.id]);
    await reload();
  }
}

_NearPin? _findNearestPin(LatLng target, List<HeritagePin> pins) {
  if (pins.isEmpty) return null;
  HeritagePin? best;
  double bestDist = double.infinity;
  for (final pin in pins) {
    final d = _haversineMeters(target.latitude, target.longitude, pin.latitude, pin.longitude);
    if (d < bestDist) {
      bestDist = d;
      best = pin;
    }
  }
  if (best == null) return null;
  return _NearPin(pin: best, distanceMeters: bestDist);
}

class _NearPin {
  _NearPin({required this.pin, required this.distanceMeters});
  final HeritagePin pin;
  final double distanceMeters;
}

double _haversineMeters(double lat1, double lon1, double lat2, double lon2) {
  const r = 6371000.0;
  final dLat = _degToRad(lat2 - lat1);
  final dLon = _degToRad(lon2 - lon1);
  final a = math.sin(dLat / 2) * math.sin(dLat / 2) +
      math.cos(_degToRad(lat1)) * math.cos(_degToRad(lat2)) * math.sin(dLon / 2) * math.sin(dLon / 2);
  final c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a));
  return r * c;
}

double _degToRad(double deg) => deg * (math.pi / 180.0);
