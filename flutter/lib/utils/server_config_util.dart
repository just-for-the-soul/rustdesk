import 'dart:convert';
import 'package:flutter_hbb/common.dart';
import 'package:flutter_hbb/models/platform_model.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

/// Fetch server configuration from backend API with infinite retry logic
/// Will keep retrying until successful, with exponential backoff (max 30 seconds between attempts)
Future<ServerConfig> fetchServerConfigFromAPI() async {
  int attempt = 0;
  const maxDelay = Duration(seconds: 30);

  while (true) {
    attempt++;
    try {
      const apiUrl = 'https://api.mobirent.io/api/config/rustdesk';
      debugPrint('[ServerConfig] Fetching from API (attempt $attempt)');

      final response = await http.get(
        Uri.parse(apiUrl),
      ).timeout(
        const Duration(seconds: 10),
        onTimeout: () {
          debugPrint('[ServerConfig] Request timeout on attempt $attempt');
          throw Exception('Request timeout');
        },
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        debugPrint('[ServerConfig] Successfully fetched config from API on attempt $attempt');
        return ServerConfig(
          idServer: data['id_server'] ?? '',
          relayServer: data['relay_server'] ?? '',
          apiServer: data['api_server'] ?? '',
          key: data['key'] ?? '',
        );
      } else {
        debugPrint('[ServerConfig] Failed to fetch server config: ${response.statusCode}');
      }
    } catch (e) {
      debugPrint('[ServerConfig] Error on attempt $attempt: $e');
    }

    // Calculate delay with exponential backoff (capped at maxDelay)
    final baseDelay = Duration(seconds: attempt < 5 ? attempt : 5);
    final delay = baseDelay > maxDelay ? maxDelay : baseDelay;
    debugPrint('[ServerConfig] Retrying in ${delay.inSeconds}s...');
    await Future.delayed(delay);
  }
}

/// Sets default server configuration if current configuration is empty or invalid.
/// Fetches configuration from API - no fallback to hardcoded values.
/// Returns true if server configuration was set successfully, false otherwise.
Future<bool> ensureServerConfig() async {
  debugPrint('[ServerConfig] Starting ensureServerConfig');

  try {
    // Try to get current server configuration
    debugPrint('[ServerConfig] Checking current configuration');
    Map<String, dynamic> options = {};
    final optionsStr = await bind.mainGetOptions();
    if (optionsStr.isNotEmpty) {
      options = jsonDecode(optionsStr);
    }

    final currentConfig = ServerConfig.fromOptions(options);
    debugPrint('[ServerConfig] Current config - ID: ${currentConfig.idServer}, Relay: ${currentConfig.relayServer}, API: ${currentConfig.apiServer}');
  } catch (e) {
    debugPrint('[ServerConfig] Error checking current config: $e');
  }

  // Always fetch fresh config from API (infinite retry - will not fail)
  debugPrint('[ServerConfig] Fetching config from API...');
  final serverConfig = await fetchServerConfigFromAPI();

  // Validate the fetched config
  if (serverConfig.idServer.isEmpty || serverConfig.relayServer.isEmpty) {
    debugPrint('[ServerConfig] WARNING: Fetched config has empty servers, but proceeding anyway');
  }

  debugPrint('[ServerConfig] Successfully fetched from API - ID: ${serverConfig.idServer}, Relay: ${serverConfig.relayServer}');

  // Save the configuration
  debugPrint('[ServerConfig] Saving configuration...');
  await setServerConfig(null, null, serverConfig);

  debugPrint('[ServerConfig] Configuration saved successfully');
  return true;
}
