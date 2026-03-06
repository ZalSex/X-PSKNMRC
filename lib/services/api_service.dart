import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class ApiService {
  static const String _serverUrl = 'https://twenty-latest-accommodation-styles.trycloudflare.com';
  static String _baseUrl = _serverUrl;

  static Future<void> init() async {
    _baseUrl = _serverUrl;
    // Simpan ke prefs supaya Kotlin service bisa baca
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('serverUrl', _serverUrl);
  }

  static Future<void> setBaseUrl(String url) async {
    // Diabaikan, selalu pakai hardcoded URL
    _baseUrl = _serverUrl;
  }

  static String get baseUrl => _baseUrl;

  static Future<Map<String, dynamic>> post(String path, Map<String, dynamic> body,
      {String? token}) async {
    final uri = Uri.parse('$_baseUrl$path');
    final headers = <String, String>{
      'Content-Type': 'application/json',
      if (token != null) 'Authorization': 'Bearer $token',
    };
    final res = await http.post(uri, headers: headers, body: jsonEncode(body))
        .timeout(const Duration(seconds: 15));
    return jsonDecode(res.body) as Map<String, dynamic>;
  }

  static Future<Map<String, dynamic>> get(String path, {String? token}) async {
    final uri = Uri.parse('$_baseUrl$path');
    final headers = <String, String>{
      if (token != null) 'Authorization': 'Bearer $token',
    };
    final res = await http.get(uri, headers: headers)
        .timeout(const Duration(seconds: 15));
    return jsonDecode(res.body) as Map<String, dynamic>;
  }
}
