import '../models/chat_message.dart';
import '../services/chat_service.dart';

/// Paginacion hacia atras del chat (CU23, solo lectura), sin widgets.
///
/// Pide `fetchBefore(menor seq cargado)` y le pasa la pagina a
/// [alRecibir], que la antepone con la misma fusion por seq que usa el
/// sondeo. [hayMasViejos] sale del hasMore de la ultima pagina hacia atras
/// (o de la carga inicial, via [inicializar]).
///
/// - Nunca hay dos cargas a la vez: mientras una esta en curso,
///   [cargarAnteriores] no hace nada.
/// - Un fallo no toca lo ya cargado: queda en [ultimoError] y
///   [hayMasViejos] sigue en true para poder reintentar.
/// - Despues de [dispose] no se llama a ningun callback.
class HistorialChat {
  HistorialChat({
    required this.traer,
    required this.primerSeq,
    required this.alRecibir,
    this.alCambiar,
  });

  /// Normalmente `(seq) => servicio.fetchBefore(projectId, seq)`.
  final Future<ChatPage> Function(int beforeSeq) traer;

  /// El seq mas bajo que ya tiene la pantalla (null si no hay mensajes).
  final int? Function() primerSeq;

  /// Tiene que fusionar los mensajes en el acto: el proximo pedido usa
  /// [primerSeq].
  final void Function(List<ChatMessage> mensajes) alRecibir;

  /// Avisa cada vez que cambia [cargando], [hayMasViejos] o [ultimoError]
  /// (la pantalla redibuja el indicador o el aviso).
  final void Function()? alCambiar;

  bool _hayMasViejos = false;
  bool _cargando = false;
  bool _cerrado = false;
  ChatException? _ultimoError;

  bool get hayMasViejos => _hayMasViejos;
  bool get cargando => _cargando;
  ChatException? get ultimoError => _ultimoError;

  /// Se puede pedir otra pagina ahora mismo.
  bool get puedeCargar => !_cerrado && !_cargando && _hayMasViejos && primerSeq() != null;

  /// Con el hasMore de la carga inicial (fetchLatest): true = hay mas viejos.
  void inicializar({required bool hayMasViejos}) {
    _hayMasViejos = hayMasViejos;
    _ultimoError = null;
  }

  /// Devuelve true si se cargo una pagina. false si no correspondia cargar
  /// (en curso, no hay mas, sin mensajes) o si fallo (ver [ultimoError]).
  Future<bool> cargarAnteriores() async {
    if (!puedeCargar) return false;
    final desde = primerSeq()!;
    _cargando = true;
    _ultimoError = null;
    alCambiar?.call();
    try {
      final pagina = await traer(desde);
      if (_cerrado) return false;
      alRecibir(pagina.messages);
      final avanzo = (primerSeq() ?? desde) < desde;
      // Si el servidor dice "hay mas" pero no trajo nada anterior, pedir
      // otra vez con el mismo cursor daria lo mismo para siempre.
      _hayMasViejos = pagina.hasMore && avanzo;
      return true;
    } catch (e) {
      if (_cerrado) return false;
      _ultimoError = e is ChatException ? e : ChatException(-1, 'Ocurrió un error al cargar los mensajes anteriores.');
      return false;
    } finally {
      _cargando = false;
      if (!_cerrado) alCambiar?.call();
    }
  }

  void dispose() => _cerrado = true;
}
