import 'dart:convert';

import 'package:http/http.dart' as http;

import 'config_service.dart';

class AuthException implements Exception {
  AuthException(this.mensaje);
  final String mensaje;

  @override
  String toString() => mensaje;
}

/// POST /api/v1/auth/login -- el MISMO endpoint y la misma forma de
/// respuesta que ya usa web/src/services/authService.ts (token, tokenType,
/// userId, email, fullName). No es un login propio de la app: es el mismo
/// backend de ModelCollab, la misma cuenta que en la web.
class AuthService {
  AuthService._();

  static Future<Sesion> iniciarSesion({
    required String direccionServidor,
    required String email,
    required String password,
  }) async {
    final uri = Uri.parse('$direccionServidor/api/v1/auth/login');
    final http.Response respuesta;
    try {
      respuesta = await http
          .post(
            uri,
            headers: {'Content-Type': 'application/json'},
            body: jsonEncode({'email': email, 'password': password}),
          )
          .timeout(const Duration(seconds: 10));
    } catch (e) {
      throw AuthException('No se pudo conectar con "$direccionServidor". Revisá la dirección del servidor. ($e)');
    }

    if (respuesta.statusCode == 401) {
      throw AuthException('Email o contraseña incorrectos.');
    }
    if (respuesta.statusCode != 200) {
      throw AuthException('El servidor respondió ${respuesta.statusCode}.');
    }

    final Map<String, dynamic> cuerpo;
    try {
      cuerpo = jsonDecode(respuesta.body) as Map<String, dynamic>;
    } catch (_) {
      throw AuthException('La respuesta del servidor no es válida.');
    }

    final sesion = Sesion(
      token: cuerpo['token'] as String,
      userId: cuerpo['userId'] as String,
      email: cuerpo['email'] as String,
      nombreCompleto: (cuerpo['fullName'] as String?) ?? (cuerpo['email'] as String),
      direccionServidor: direccionServidor,
    );
    await ConfigService.guardarSesion(sesion);
    return sesion;
  }
}
