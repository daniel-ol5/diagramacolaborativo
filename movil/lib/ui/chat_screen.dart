import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../chat/chat_merge.dart';
import '../chat/chat_poller.dart';
import '../chat/espera_ia.dart';
import '../chat/formato.dart';
import '../chat/uuid_v4.dart';
import '../models/chat_message.dart';
import '../services/chat_service.dart';
import '../services/config_service.dart';
import '../voz/servicio_de_voz.dart';
import 'error_view.dart';

/// Chat del proyecto con la IA (CU19), por REST + sondeo. Todo texto se
/// muestra con Text plano, sea cual sea el kind: nada de Markdown ni enlaces.
class ChatScreen extends StatefulWidget {
  const ChatScreen({
    super.key,
    required this.sesion,
    required this.projectId,
    required this.projectName,
    this.servicio,
    this.voz,
  });

  final Sesion sesion;
  final String projectId;
  final String projectName;

  /// Solo para tests; en la app se arma con la Sesion.
  final ChatService? servicio;

  /// Solo para tests; en la app es el mismo ServicioDeVoz que usa
  /// VoiceCommandScreen (sin modificarlo).
  final ServicioDeVoz? voz;

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> with WidgetsBindingObserver {
  late final ChatService _chat = widget.servicio ??
      ChatService(direccionServidor: widget.sesion.direccionServidor, token: widget.sesion.token);

  late final ChatPoller _poller = ChatPoller(
    traer: (seq) => _chat.fetchAfter(widget.projectId, seq),
    ultimoSeq: () => _timeline.ultimoSeq,
    alRecibir: _alRecibirSondeo,
    alFallar: _alFallarSondeo,
  );

  final _timeline = ChatTimeline();
  final _esperaIa = EsperaIa();
  Timer? _timerIa;

  /// Se crea al primer toque del microfono: recien ahi Android pide el
  /// permiso, no al abrir el chat.
  ServicioDeVoz? _voz;
  bool _vozLista = false;
  bool _escuchando = false;

  final _texto = TextEditingController();
  final _scroll = ScrollController();
  final _foco = FocusNode();

  bool _cargando = true;

  /// Error de la carga inicial (todavia no hay nada que mostrar).
  String? _errorCarga;

  /// Hubo un fallo de red: el campo queda deshabilitado hasta que un
  /// pedido (el sondeo o "Reintentar") vuelva a salir bien.
  bool _sinConexion = false;
  bool _noMiembro = false;
  bool _sesionVencida = false;
  String? _ultimoErrorSondeo;

  /// false si el usuario subio a leer: entonces no se lo arrastra al final.
  bool _seguirAlFinal = true;

  static const int _avisoLongitud = 1800;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _scroll.addListener(_alDesplazar);
    _cargarInicial();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _poller.dispose();
    _timerIa?.cancel();
    _voz?.detener();
    _texto.dispose();
    _scroll.dispose();
    _foco.dispose();
    super.dispose();
  }

  /// En segundo plano no se sondea (bateria y datos); al volver se pide lo
  /// nuevo en el acto en vez de esperar 3 s.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    switch (state) {
      case AppLifecycleState.resumed:
        if (_puedeSondear) _poller.iniciar(inmediato: true);
      case AppLifecycleState.paused:
      case AppLifecycleState.hidden:
      case AppLifecycleState.detached:
        _poller.pausar();
        _voz?.detener();
      case AppLifecycleState.inactive:
        break;
    }
  }

  bool get _puedeSondear => !_cargando && _errorCarga == null && !_noMiembro && !_sesionVencida;

  // ------------------------------------------------------------ carga y sondeo

  Future<void> _cargarInicial() async {
    setState(() {
      _cargando = true;
      _errorCarga = null;
    });
    try {
      final pagina = await _chat.fetchLatest(widget.projectId);
      if (!mounted) return;
      setState(() {
        _timeline.fusionar(pagina.messages);
        _cargando = false;
        _sinConexion = false;
      });
      _bajarAlFinal(forzar: true);
      final estado = WidgetsBinding.instance.lifecycleState;
      if (estado == null || estado == AppLifecycleState.resumed) _poller.iniciar();
    } on ChatException catch (e) {
      if (!mounted) return;
      setState(() {
        _cargando = false;
        _errorCarga = e.mensaje;
        _noMiembro = e.noEsMiembro;
        _sesionVencida = e.status == 401;
      });
    }
  }

  void _alRecibirSondeo(List<ChatMessage> mensajes) {
    if (!mounted) return;
    setState(() {
      final nuevos = _timeline.fusionar(mensajes);
      _sinConexion = false;
      _ultimoErrorSondeo = null;
      if (_esperaIa.procesar(nuevos, widget.sesion.userId)) _timerIa?.cancel();
    });
    _bajarAlFinal();
  }

  void _alFallarSondeo(ChatException e) {
    if (!mounted) return;
    _registrarError(e);
    // Sin red, el aviso fijo ya lo dice. Otro error se avisa una sola vez
    // y no cada 3 s mientras siga igual.
    if (!e.sinConexion && e.mensaje != _ultimoErrorSondeo) _avisar(e.mensaje);
    _ultimoErrorSondeo = e.mensaje;
  }

  // ------------------------------------------------------------ envio

  bool get _campoHabilitado => !_sinConexion && !_noMiembro && !_sesionVencida;

  void _enviar() {
    final texto = _texto.text;
    if (!_campoHabilitado || texto.trim().isEmpty || texto.length > ChatService.maxTexto) return;
    final cmid = uuidV4();
    setState(() {
      _timeline.agregarPendiente(cmid, texto, DateTime.now().toUtc());
      _texto.clear();
    });
    _bajarAlFinal(forzar: true);
    _publicar(cmid);
  }

  /// Envia (o reenvia) un pendiente con SU clientMessageId: si el primer
  /// intento si llego al servidor, este devuelve el original (200) y no
  /// se duplica.
  Future<void> _publicar(String cmid) async {
    final p = _timeline.pendiente(cmid);
    if (p == null) return;
    setState(() => _timeline.marcarEnviando(cmid));
    try {
      final m = await _chat.send(widget.projectId, p.text, cmid);
      if (!mounted) return;
      setState(() {
        _timeline.fusionar([m]);
        _sinConexion = false;
        if (mencionaIa(p.text)) _empezarEsperaIa(m.seq);
      });
      _bajarAlFinal();
    } on ChatException catch (e) {
      if (!mounted) return;
      setState(() => _timeline.marcarFallido(cmid, e.mensaje));
      _registrarError(e);
      _avisar(e.mensaje);
    }
  }

  /// Llamar dentro de setState. Si la respuesta ya llego (el sondeo le
  /// gano al POST), la espera termina en el acto.
  void _empezarEsperaIa(int seq) {
    _timerIa?.cancel();
    _esperaIa.iniciar(seq, DateTime.now());
    if (_esperaIa.procesar(_timeline.mensajes, widget.sesion.userId)) return;
    _timerIa = Timer(_esperaIa.limite, () {
      if (!mounted) return;
      setState(_esperaIa.terminar);
    });
  }

  void _registrarError(ChatException e) {
    setState(() {
      if (e.sinConexion) _sinConexion = true;
      if (e.noEsMiembro) _noMiembro = true;
      if (e.status == 401) _sesionVencida = true;
    });
    // Sin permiso o sin sesion, seguir sondeando no tiene sentido.
    if (e.noEsMiembro || e.status == 401) _poller.pausar();
  }

  /// Inserta "@IA " donde esta el cursor, separado de la palabra anterior
  /// (si quedara pegado, "hola@IA", no seria una mencion).
  void _preguntarIa() => _insertarEnCursor('@IA ', recortable: false);

  /// Inserta [texto] en el cursor (o reemplaza la seleccion), con un
  /// espacio delante si quedaria pegado a la palabra anterior. Respeta el
  /// limite de 2000: si no entra, se recorta (o, si no es [recortable],
  /// no se inserta, para no dejar un "@I" a medias).
  void _insertarEnCursor(String texto, {bool recortable = true}) {
    final v = _texto.value;
    final sel = v.selection;
    final inicio = sel.isValid ? sel.start : v.text.length;
    final fin = sel.isValid ? sel.end : v.text.length;
    final hacerEspacio = inicio > 0 && v.text[inicio - 1].trim().isNotEmpty;
    var insertar = '${hacerEspacio ? ' ' : ''}$texto';
    final lugar = ChatService.maxTexto - (v.text.length - (fin - inicio));
    if (insertar.length > lugar) {
      if (!recortable || lugar <= 0) {
        _avisar('El mensaje ya llegó al límite de ${ChatService.maxTexto} caracteres.');
        return;
      }
      insertar = insertar.substring(0, lugar);
    }
    _texto.value = TextEditingValue(
      text: v.text.replaceRange(inicio, fin, insertar),
      selection: TextSelection.collapsed(offset: inicio + insertar.length),
    );
    _foco.requestFocus();
  }

  /// Dicta con el mismo ServicioDeVoz de VoiceCommandScreen y deja el texto
  /// EN EL CAMPO, sin enviarlo: la persona lo revisa y decide.
  Future<void> _dictar() async {
    if (_escuchando || !_campoHabilitado) return;
    final voz = _voz ??= widget.voz ?? ServicioDeVoz();
    if (!_vozLista) {
      // iniciar() pide el permiso; devuelve false sin permiso o sin motor de
      // reconocimiento (no distingue cual). Se reintenta en el proximo toque.
      _vozLista = await voz.iniciar();
      if (!mounted) return;
      if (!_vozLista) {
        _avisar('No se puede usar el micrófono. Revisa que la app tenga permiso de micrófono '
            'y que el teléfono tenga reconocimiento de voz.');
        return;
      }
    }
    setState(() => _escuchando = true);
    final texto = await voz.escuchar();
    if (!mounted) return;
    setState(() => _escuchando = false);
    if (texto.trim().isEmpty) {
      _avisar('No se entendió nada. Intenta de nuevo.');
      return;
    }
    _insertarEnCursor(texto.trim());
  }

  void _avisar(String mensaje) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(mensaje)));
  }

  // ------------------------------------------------------------ scroll

  void _alDesplazar() {
    if (!_scroll.hasClients) return;
    final pos = _scroll.position;
    _seguirAlFinal = pos.pixels >= pos.maxScrollExtent - 72;
  }

  void _bajarAlFinal({bool forzar = false}) {
    if (!forzar && !_seguirAlFinal) return;
    _seguirAlFinal = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients) return;
      _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
  }

  // ------------------------------------------------------------ UI

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Chat del proyecto'),
            Text(
              widget.projectName,
              style: Theme.of(context).textTheme.bodySmall,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
      ),
      body: _cuerpo(),
    );
  }

  Widget _cuerpo() {
    if (_cargando) return const Center(child: CircularProgressIndicator());
    if (_errorCarga != null) return ErrorView(mensaje: _errorCarga!, onReintentar: _cargarInicial);

    return Column(
      children: [
        if (_sinConexion) _AvisoSinConexion(onReintentar: _poller.ciclo),
        // Tocar la conversacion baja el teclado para poder leerla entera.
        // translucent: los botones de adentro (Reintentar) siguen andando.
        // Fuera de la semantica: si no, TalkBack anuncia toda la lista como
        // un boton sin nombre (lo detecto el test de accesibilidad).
        Expanded(
          child: GestureDetector(
            behavior: HitTestBehavior.translucent,
            excludeFromSemantics: true,
            onTap: _foco.unfocus,
            child: _lista(),
          ),
        ),
        if (_esperaIa.activa(DateTime.now())) const _IaRespondiendo(),
        if (_escuchando) const _Escuchando(),
        _entrada(),
      ],
    );
  }

  Widget _lista() {
    if (_timeline.vacio) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(24),
          child: Text(
            'Todavía no hay mensajes.\n\nEscribe @IA en tu mensaje para preguntarle a la IA.',
            textAlign: TextAlign.center,
          ),
        ),
      );
    }
    final mensajes = _timeline.mensajes;
    final pendientes = _timeline.pendientes;
    return ListView.builder(
      controller: _scroll,
      // Deslizar la lista tambien baja el teclado.
      keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      itemCount: mensajes.length + pendientes.length,
      itemBuilder: (context, i) {
        if (i < mensajes.length) {
          final m = mensajes[i];
          if (m.esDeSistema) return _MensajeSistema(mensaje: m);
          return _BurbujaMensaje(mensaje: m, esMio: !m.esDeIa && m.authorId == widget.sesion.userId);
        }
        final p = pendientes[i - mensajes.length];
        return _BurbujaPendiente(
          pendiente: p,
          autor: widget.sesion.nombreCompleto,
          onReintentar: () => _publicar(p.clientMessageId),
        );
      },
    );
  }

  Widget _entrada() {
    final cs = Theme.of(context).colorScheme;
    final habilitado = _campoHabilitado;
    final pista = _noMiembro
        ? 'No eres miembro de este proyecto.'
        : _sesionVencida
            ? 'Tu sesión expiró. Vuelve a iniciar sesión.'
            : _sinConexion
                ? 'Sin conexión: no puedes enviar mensajes.'
                : 'Escribe un mensaje';

    return Material(
      color: cs.surfaceContainer,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(8, 4, 4, 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  TextButton.icon(
                    onPressed: habilitado ? _preguntarIa : null,
                    style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
                    icon: const Icon(Icons.smart_toy_outlined),
                    label: const Text('Preguntar a la IA'),
                  ),
                ],
              ),
              Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Expanded(
                    child: TextField(
                      controller: _texto,
                      focusNode: _foco,
                      enabled: habilitado,
                      minLines: 1,
                      maxLines: 5,
                      maxLength: ChatService.maxTexto,
                      maxLengthEnforcement: MaxLengthEnforcement.enforced,
                      keyboardType: TextInputType.multiline,
                      textCapitalization: TextCapitalization.sentences,
                      decoration: InputDecoration(
                        hintText: pista,
                        border: const OutlineInputBorder(),
                        isDense: true,
                      ),
                      // El contador solo aparece cerca del limite.
                      buildCounter: (context, {required currentLength, required isFocused, maxLength}) {
                        if (maxLength == null || currentLength < _avisoLongitud) return null;
                        return Text(
                          '$currentLength / $maxLength',
                          semanticsLabel: 'Quedan ${maxLength - currentLength} caracteres',
                          style: TextStyle(color: currentLength >= maxLength ? cs.error : cs.onSurfaceVariant),
                        );
                      },
                    ),
                  ),
                  IconButton(
                    onPressed: habilitado && !_escuchando ? _dictar : null,
                    tooltip: _escuchando ? 'Escuchando…' : 'Dictar mensaje',
                    icon: Icon(_escuchando ? Icons.mic : Icons.mic_none),
                    color: _escuchando ? cs.error : null,
                  ),
                  ValueListenableBuilder<TextEditingValue>(
                    valueListenable: _texto,
                    builder: (context, valor, _) {
                      final puede = habilitado && valor.text.trim().isNotEmpty;
                      return IconButton(
                        onPressed: puede ? _enviar : null,
                        tooltip: 'Enviar',
                        icon: const Icon(Icons.send),
                        color: cs.primary,
                      );
                    },
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ------------------------------------------------------------ piezas

class _AvisoSinConexion extends StatelessWidget {
  const _AvisoSinConexion({required this.onReintentar});

  final VoidCallback onReintentar;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Semantics(
      liveRegion: true,
      child: Material(
        color: cs.errorContainer,
        child: Padding(
          padding: const EdgeInsets.only(left: 16, right: 4),
          child: Row(
            children: [
              Icon(Icons.cloud_off, color: cs.onErrorContainer),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  'Sin conexión con el servidor.',
                  style: TextStyle(color: cs.onErrorContainer),
                ),
              ),
              TextButton(
                onPressed: onReintentar,
                style: TextButton.styleFrom(minimumSize: const Size(48, 48), foregroundColor: cs.onErrorContainer),
                child: const Text('Reintentar'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _IaRespondiendo extends StatelessWidget {
  const _IaRespondiendo();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Semantics(
      liveRegion: true,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
        child: Row(
          children: [
            SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2, color: cs.primary)),
            const SizedBox(width: 8),
            Flexible(
              child: Text(
                'La IA está respondiendo…',
                style: TextStyle(fontSize: 13, fontStyle: FontStyle.italic, color: cs.onSurfaceVariant),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Escuchando extends StatelessWidget {
  const _Escuchando();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Semantics(
      liveRegion: true,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
        child: Row(
          children: [
            Icon(Icons.mic, size: 18, color: cs.error),
            const SizedBox(width: 8),
            Flexible(
              child: Text(
                'Escuchando… habla ahora (hasta 8 segundos).',
                style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _MensajeSistema extends StatelessWidget {
  const _MensajeSistema({required this.mensaje});

  final ChatMessage mensaje;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 24),
      child: Semantics(
        container: true,
        label: 'Aviso del sistema, ${horaLocal(mensaje.createdAt)}: ${mensaje.text}',
        child: ExcludeSemantics(
          child: Text(
            '${mensaje.text} · ${horaLocal(mensaje.createdAt)}',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 12, fontStyle: FontStyle.italic, color: cs.onSurfaceVariant),
          ),
        ),
      ),
    );
  }
}

class _BurbujaMensaje extends StatelessWidget {
  const _BurbujaMensaje({required this.mensaje, required this.esMio});

  final ChatMessage mensaje;
  final bool esMio;

  @override
  Widget build(BuildContext context) {
    final m = mensaje;
    final encabezado = m.esDeIa
        ? (m.addressedToName != null ? 'IA · para ${m.addressedToName}' : 'IA')
        : m.authorName;
    return _Burbuja(
      alDerecha: esMio,
      esIa: m.esDeIa,
      colorPunto: colorDesdeHex(m.authorColor),
      encabezado: encabezado,
      texto: m.text,
      hora: horaLocal(m.createdAt),
      descripcion: esMio ? 'Tú' : encabezado,
    );
  }
}

class _BurbujaPendiente extends StatelessWidget {
  const _BurbujaPendiente({required this.pendiente, required this.autor, required this.onReintentar});

  final MensajePendiente pendiente;
  final String autor;
  final VoidCallback onReintentar;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final p = pendiente;
    final Widget pie = p.fallido
        ? Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.error_outline, size: 16, color: cs.error),
              const SizedBox(width: 4),
              Flexible(
                child: Text('No se envió', style: TextStyle(color: cs.error, fontSize: 12)),
              ),
              TextButton(
                onPressed: onReintentar,
                style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
                child: Semantics(label: 'Reintentar enviar el mensaje', excludeSemantics: true, child: const Text('Reintentar')),
              ),
            ],
          )
        : Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const SizedBox(width: 12, height: 12, child: CircularProgressIndicator(strokeWidth: 2)),
                const SizedBox(width: 6),
                Text('Enviando…', style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
              ],
            ),
          );
    return _Burbuja(
      alDerecha: true,
      esIa: false,
      colorPunto: null,
      encabezado: autor,
      texto: p.text,
      hora: horaLocal(p.creado),
      descripcion: 'Tú',
      estado: p.fallido ? 'no se envió' : 'enviando',
      pie: pie,
    );
  }
}

class _Burbuja extends StatelessWidget {
  const _Burbuja({
    required this.alDerecha,
    required this.esIa,
    required this.colorPunto,
    required this.encabezado,
    required this.texto,
    required this.hora,
    required this.descripcion,
    this.estado,
    this.pie,
  });

  final bool alDerecha;
  final bool esIa;
  final Color? colorPunto;
  final String encabezado;
  final String texto;
  final String hora;

  /// Quien habla, para el lector de pantalla.
  final String descripcion;
  final String? estado;
  final Widget? pie;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final tema = Theme.of(context).textTheme;
    final fondo = alDerecha ? cs.primaryContainer : (esIa ? cs.surface : cs.surfaceContainerHighest);
    final colorTexto = alDerecha ? cs.onPrimaryContainer : cs.onSurface;
    final colorSecundario = alDerecha ? cs.onPrimaryContainer : cs.onSurfaceVariant;

    final burbuja = Container(
      constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * 0.8),
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      decoration: BoxDecoration(
        color: fondo,
        borderRadius: BorderRadius.circular(12),
        border: esIa ? Border.all(color: cs.primary, width: 2) : null,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 10,
                height: 10,
                decoration: BoxDecoration(color: colorPunto ?? Colors.grey, shape: BoxShape.circle),
              ),
              const SizedBox(width: 6),
              Flexible(
                child: Text(
                  encabezado,
                  overflow: TextOverflow.ellipsis,
                  style: tema.labelMedium?.copyWith(
                    fontWeight: FontWeight.w600,
                    color: esIa ? cs.primary : colorSecundario,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Text(hora, style: tema.labelSmall?.copyWith(color: colorSecundario)),
            ],
          ),
          const SizedBox(height: 4),
          Text(texto, style: tema.bodyMedium?.copyWith(color: colorTexto)),
        ],
      ),
    );

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: alDerecha ? CrossAxisAlignment.end : CrossAxisAlignment.start,
        children: [
          Semantics(
            container: true,
            label: '$descripcion, $hora${estado != null ? ', $estado' : ''}: $texto',
            child: ExcludeSemantics(child: burbuja),
          ),
          ?pie,
        ],
      ),
    );
  }
}
