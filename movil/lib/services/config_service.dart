import 'package:shared_preferences/shared_preferences.dart';

/// LA DIRECCION DEL SERVIDOR Y EL TOKEN VIVEN ACA, Y SOLO ACA.
///
/// A diferencia de una app generada por CU19 (que fija su backend con
/// --dart-define en tiempo de compilacion, porque esta atada a UN diagrama
/// para siempre), esta app acompaña al usuario entre distintos entornos a
/// lo largo del proyecto: hoy una IP de LAN, mañana el tunel HTTPS, mas
/// adelante el dominio real desplegado. Una app instalada no se recompila
/// para apuntar a otro servidor -- por eso la direccion es un campo que se
/// edita en la pantalla de login, guardado junto al token, nunca una
/// constante del codigo.
class ConfigService {
  ConfigService._();

  static const String _claveDireccion = 'direccion_servidor';
  static const String _claveToken = 'token';
  static const String _claveUserId = 'user_id';
  static const String _claveEmail = 'email';
  static const String _claveNombre = 'nombre_completo';
  static const String _claveUltimoProyectoId = 'ultimo_proyecto_id';
  static const String _claveUltimoProyectoNombre = 'ultimo_proyecto_nombre';
  static const String _claveUltimoDiagramaId = 'ultimo_diagrama_id';
  static const String _claveUltimoDiagramaNombre = 'ultimo_diagrama_nombre';

  /// Servidor por defecto en producción (Azure con TLS). Si se corre en LAN
  /// o túnel de desarrollo, el usuario puede editar la dirección desde el login.
  static const String direccionPorDefecto = 'https://modelcollab.duckdns.org';

  static Future<String> direccionServidor() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_claveDireccion) ?? direccionPorDefecto;
  }

  static Future<void> guardarDireccionServidor(String direccion) async {
    final prefs = await SharedPreferences.getInstance();
    final limpia = direccion.trim().replaceAll(RegExp(r'/+$'), '');
    await prefs.setString(_claveDireccion, limpia);
  }

  static Future<Sesion?> sesionGuardada() async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString(_claveToken);
    final userId = prefs.getString(_claveUserId);
    final email = prefs.getString(_claveEmail);
    if (token == null || userId == null || email == null) return null;
    return Sesion(
      token: token,
      userId: userId,
      email: email,
      nombreCompleto: prefs.getString(_claveNombre) ?? email,
      direccionServidor: prefs.getString(_claveDireccion) ?? direccionPorDefecto,
    );
  }

  static Future<void> guardarSesion(Sesion sesion) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_claveDireccion, sesion.direccionServidor);
    await prefs.setString(_claveToken, sesion.token);
    await prefs.setString(_claveUserId, sesion.userId);
    await prefs.setString(_claveEmail, sesion.email);
    await prefs.setString(_claveNombre, sesion.nombreCompleto);
  }

  /// Cerrar sesion borra el token pero NO la direccion del servidor -- es
  /// una preferencia del dispositivo, no de la persona logueada, y
  /// mantenerla evita que alguien tenga que volver a escribirla para
  /// entrar de nuevo al mismo servidor. Tampoco borra el ultimo destino: si
  /// la misma persona vuelve a entrar, sigue parada frente al mismo
  /// diagrama.
  static Future<void> cerrarSesion() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_claveToken);
    await prefs.remove(_claveUserId);
    await prefs.remove(_claveEmail);
    await prefs.remove(_claveNombre);
  }

  /// Caso real: parado frente a la pizarra, con el celular en la mano. Si
  /// para sacar una foto hay que entrar, elegir proyecto y elegir diagrama
  /// cada vez, la funcion es incomoda justo donde tiene que ser rapida --
  /// por eso el ultimo destino se recuerda y la pantalla principal ofrece
  /// ir directo ahi, con el cambio de diagrama a un toque de distancia.
  static Future<UltimoDestino?> ultimoDestino() async {
    final prefs = await SharedPreferences.getInstance();
    final proyectoId = prefs.getString(_claveUltimoProyectoId);
    final proyectoNombre = prefs.getString(_claveUltimoProyectoNombre);
    final diagramaId = prefs.getString(_claveUltimoDiagramaId);
    final diagramaNombre = prefs.getString(_claveUltimoDiagramaNombre);
    if (proyectoId == null || proyectoNombre == null || diagramaId == null || diagramaNombre == null) {
      return null;
    }
    return UltimoDestino(
      proyectoId: proyectoId,
      proyectoNombre: proyectoNombre,
      diagramaId: diagramaId,
      diagramaNombre: diagramaNombre,
    );
  }

  static Future<void> guardarUltimoDestino(UltimoDestino destino) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_claveUltimoProyectoId, destino.proyectoId);
    await prefs.setString(_claveUltimoProyectoNombre, destino.proyectoNombre);
    await prefs.setString(_claveUltimoDiagramaId, destino.diagramaId);
    await prefs.setString(_claveUltimoDiagramaNombre, destino.diagramaNombre);
  }
}

class UltimoDestino {
  const UltimoDestino({
    required this.proyectoId,
    required this.proyectoNombre,
    required this.diagramaId,
    required this.diagramaNombre,
  });

  final String proyectoId;
  final String proyectoNombre;
  final String diagramaId;
  final String diagramaNombre;
}

class Sesion {
  const Sesion({
    required this.token,
    required this.userId,
    required this.email,
    required this.nombreCompleto,
    required this.direccionServidor,
  });

  final String token;
  final String userId;
  final String email;
  final String nombreCompleto;
  final String direccionServidor;
}
