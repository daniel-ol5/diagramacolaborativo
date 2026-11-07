import '../models/chat_message.dart';

/// "@IA" como palabra completa, sin distinguir mayusculas: "@ia hola" y
/// "hola, @IA?" si; "hola@IA" y "@IAs" no. Es la misma regla con la que
/// el backend decide si la IA contesta.
final RegExp _mencionIa = RegExp(r'(?<![\p{L}\p{N}_@])@ia(?![\p{L}\p{N}_])', caseSensitive: false, unicode: true);

bool mencionaIa(String texto) => _mencionIa.hasMatch(texto);

/// Estado de "La IA está respondiendo…", sin widgets ni timers: la
/// pantalla le pasa la hora y los mensajes nuevos.
///
/// Empieza cuando el servidor CONFIRMA un mensaje propio que menciona a la
/// IA (si el POST fallo, la IA no va a contestar). Termina SOLO cuando
/// llega un mensaje de la IA dirigido a mi posterior a ese mensaje, o a
/// los [limite]. Un aviso SYSTEM ("La IA no está disponible ahora") no la
/// corta: decision del equipo, se apaga a los 30 s. Un segundo mensaje con
/// @IA reinicia la espera.
class EsperaIa {
  EsperaIa({this.limite = const Duration(seconds: 30)});

  final Duration limite;

  int? _desdeSeq;
  DateTime? _desde;

  bool activa(DateTime ahora) => _desde != null && ahora.difference(_desde!) < limite;

  /// Cuanto falta para el limite (para programar el timer de la pantalla).
  Duration restante(DateTime ahora) {
    if (_desde == null) return Duration.zero;
    final r = limite - ahora.difference(_desde!);
    return r.isNegative ? Duration.zero : r;
  }

  void iniciar(int seqDeMiMensaje, DateTime ahora) {
    _desdeSeq = seqDeMiMensaje;
    _desde = ahora;
  }

  void terminar() {
    _desdeSeq = null;
    _desde = null;
  }

  /// Devuelve true si con estos mensajes la espera termino.
  bool procesar(Iterable<ChatMessage> mensajes, String miUserId) {
    final desde = _desdeSeq;
    if (desde == null) return false;
    for (final m in mensajes) {
      if (m.seq <= desde) continue;
      final respuestaParaMi = m.esDeIa && !m.esDeSistema && m.addressedToId == miUserId;
      if (respuestaParaMi) {
        terminar();
        return true;
      }
    }
    return false;
  }
}
