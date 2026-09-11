import 'package:flutter/services.dart';

class FamilyAccess {
  static const _channel = MethodChannel('org.traccar/family');
  static Future<void> configure(String deviceId) =>
      _channel.invokeMethod('configure', {'deviceId': deviceId});
  static Future<void> receive(Map<String, dynamic> data) =>
      _channel.invokeMethod('receive', data);
}
