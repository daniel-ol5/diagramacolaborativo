import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../chat/chat_merge.dart';
import '../chat/chat_poller.dart';
import '../chat/espera_ia.dart';
import '../chat/formato.dart';
import '../chat/historial.dart';
import '../chat/no_leidos.dart';
import '../chat/separadores_dia.dart';
import '../chat/uuid_v4.dart';
import '../models/chat_message.dart';
import '../models/chat_read_state.dart';
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
    this.alCambiarLectura,
  });

  final Sesion sesion;
  final String projectId;
  final String projectName;

  /// Solo para tests; en la app se arma con la Sesion.
  final ChatService? servicio;

  /// Solo para tests; en la app es el mismo ServicioDeVoz que usa
  /// VoiceCommandScreen (sin modificarlo).
  final ServicioDeVoz? voz;

  /// Avisa cada estado de lectura que confirma el servidor (al entrar, al
  /// marcar como leido y al salir), para que quien abrio el chat actualice
  /// el contador del icono sin esperar otro pedido.
  final void Function(ChatReadState estado)? alCambiarLectura;

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> with WidgetsBindingObserver {
  late final ChatService _chat =
      widget.servicio ?? ChatService(direccionServidor: widget.sesion.direccionServidor, token: widget.sesion.token);

  late final ChatPoller _poller = ChatPoller(
    traer: (seq) => _chat.fetchAfter(widget.projectId, seq),
    ultimoSeq: () => _timeline.ultimoSeq,
    alRecibir: _alRecibirSondeo,
    alFallar: _alFallarSondeo,
  );

  /// CU23: paginacion hacia atras (solo lectura).
  late final HistorialChat _historial = HistorialChat(
    traer: (seq) => _chat.fetchBefore(widget.projectId, seq),
    primerSeq: () => _timeline.primerSeq,
    alRecibir: (mensajes) {
      if (!mounted) return;
      final teniaSeparador = _separadorAlCentro();
      setState(() => _timeline.fusionar(mensajes));
      // La primera vez que se antepone algo del MISMO dia que el primer
      // mensaje de la carga inicial, el separador de ese dia pasa del bloque
      // de abajo al de arriba: lo de abajo subiria su altura (un salto).
      // Como la altura es fija, se corrige exacto antes del proximo frame
      // (correctPixels: sin animacion ni aviso a los listeners).
      if (teniaSeparador && !_separadorAlCentro() && _scroll.hasClients) {
        _scroll.position.correctPixels(_scroll.position.pixels - _alturaSeparador);
      }
    },
    alCambiar: () {
      if (mounted) setState(() {});
    },
  );

  /// Seq del primer mensaje de la carga inicial. Lo anterior va en el
  /// bloque de arriba del CustomScrollView, que crece HACIA ARRIBA desde
  /// el centro: anteponer no mueve lo que se esta leyendo (sin saltos).
  int? _anclaSeq;
  static const _claveCentro = ValueKey<String>('chat-centro');

  /// Altura FIJA de los separadores de dia (se recalcula con el tamaño de
  /// letra del sistema en cada build). Fija para poder compensar exacto.
  double _alturaSeparador = 40;

  final _timeline = ChatTimeline();
  final _esperaIa = EsperaIa();

  /// CU23, mensajes nuevos. El estado de lectura vive en el servidor (el
  /// mismo contador que la web): GET al entrar, PUT al leer.
  late final MarcadorLeido _marcador = MarcadorLeido(
    enviar: (seq) => _chat.markRead(widget.projectId, seq),
    alConfirmar: (estado) => widget.alCambiarLectura?.call(estado),
  );

  /// lastReadSeq que dio el servidor al entrar (null hasta que contesta o
  /// si fallo: entonces no hay linea "Mensajes nuevos").
  int? _lastReadAlEntrar;

  /// Hasta que seq habia al conocer el lastReadSeq: la linea marca lo que
  /// no habias leido AL ENTRAR, no lo que llega mientras miras.
  int? _techoLinea;

  /// No se marca como leido hasta saber el lastReadSeq de entrada (si se
  /// marcara antes, la linea "Mensajes nuevos" se perderia).
  bool _lecturaLista = false;

  /// Mensajes de otros que llegaron mientras leias mas arriba.
  int _nuevosSinVer = 0;
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

  /// Cuenta los arrastres CON EL DEDO sobre la lista. El salto al final se
  /// corta si cambia: asi distingue "el usuario subio" de las correcciones
  /// de posicion que hace Flutter solo al recalcular alturas.
  int _arrastres = 0;

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
    _historial.dispose();
    // Lo leido que quedo sin mandar se manda ya (sin esperar al temporizador),
    // y su resultado se avisa aunque esta pantalla ya no exista.
    final avisar = widget.alCambiarLectura;
    _marcador.enviarAhora().then((estado) {
      if (estado != null) avisar?.call(estado);
    });
    _marcador.dispose();
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
        _marcarSiCorresponde();
      case AppLifecycleState.paused:
      case AppLifecycleState.hidden:
      case AppLifecycleState.detached:
        _poller.pausar();
        _voz?.detener();
        _marcador.enviarAhora();
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
        _anclaSeq = _timeline.primerSeq;
        _historial.inicializar(hayMasViejos: pagina.hasMore);
        _cargando = false;
        _sinConexion = false;
      });
      _bajarAlFinal(forzar: true);
      final estado = WidgetsBinding.instance.lifecycleState;
      if (estado == null || estado == AppLifecycleState.resumed) _poller.iniciar();
      _cargarEstadoLectura();
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
      // Leyendo mas arriba: no se baja solo, se avisa con el boton.
      if (!_seguirAlFinal) _nuevosSinVer += contarNuevos(nuevos, widget.sesion.userId);
    });
    _bajarAlFinal();
    _marcarSiCorresponde();
  }

  /// GET /chat/read. Si falla, el chat sigue igual: solo no hay linea
  /// "Mensajes nuevos" (y se marca como leido igual, el servidor solo avanza).
  Future<void> _cargarEstadoLectura() async {
    try {
      final estado = await _chat.fetchReadState(widget.projectId);
      if (!mounted) return;
      _marcador.inicializar(estado.lastReadSeq);
      widget.alCambiarLectura?.call(estado);
      setState(() {
        _lastReadAlEntrar = estado.lastReadSeq;
        _techoLinea = _timeline.ultimoSeq;
      });
      _bajarAlFinal();
    } on ChatException {
      // sin linea; el resto del chat no se entera
    }
    if (!mounted) return;
    _lecturaLista = true;
    _marcarSiCorresponde();
  }

  /// Marca como leido hasta el ultimo seq SOLO si de verdad lo estas
  /// viendo: al final de la lista y con la app en primer plano.
  void _marcarSiCorresponde() {
    final ultimo = _timeline.ultimoSeq;
    if (!_lecturaLista || ultimo == null || !_seguirAlFinal || _noMiembro || _sesionVencida) return;
    final estado = WidgetsBinding.instance.lifecycleState;
    if (estado != null && estado != AppLifecycleState.resumed) return;
    _marcador.quiero(ultimo);
  }

  /// [_seqLinea] calculado una vez por dibujado (en _lista), no por fila.
  int? _seqLineaDibujo;

  /// Seq del mensaje encima del cual va la linea, o null.
  int? get _seqLinea {
    final lastRead = _lastReadAlEntrar;
    final techo = _techoLinea;
    if (lastRead == null || techo == null) return null;
    // Si todavia no se cargo ningun mensaje ya leido y quedan anteriores,
    // el primer no leido podria estar mas atras: la linea espera. Asi no
    // cambia de lugar (ni hace saltar la vista) al cargar los anteriores.
    if (_historial.hayMasViejos && !_timeline.mensajes.any((m) => m.seq <= lastRead)) return null;
    return seqLineaNuevos(
      [
        for (final m in _timeline.mensajes)
          if (m.seq <= techo) m,
      ],
      lastRead,
      widget.sesion.userId,
    );
  }

  void _irAlFinal() {
    setState(() => _nuevosSinVer = 0);
    _bajarAlFinal(forzar: true);
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
        _avisar(
          'No se puede usar el micrófono. Revisa que la app tenga permiso de micrófono '
          'y que el teléfono tenga reconocimiento de voz.',
        );
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
    if (_seguirAlFinal) {
      // Llegaste al final: lo nuevo ya se ve. En un microtask por la misma
      // razon que la carga de anteriores (puede ser en medio de un layout).
      Future.microtask(() {
        if (!mounted) return;
        if (_nuevosSinVer != 0) setState(() => _nuevosSinVer = 0);
        _marcarSiCorresponde();
      });
    }
    // Cerca del tope: carga lo anterior sola. Despues de un fallo NO
    // reintenta sola (seria un bucle); espera el boton Reintentar.
    if (pos.pixels <= pos.minScrollExtent + 300 && _historial.puedeCargar && _historial.ultimoError == null) {
      // En un microtask: este listener puede correr en medio de un layout,
      // donde setState no esta permitido.
      Future.microtask(_cargarAnteriores);
    }
  }

  Future<void> _cargarAnteriores() async {
    final ok = await _historial.cargarAnteriores();
    if (!mounted || ok) return;
    final e = _historial.ultimoError;
    // Sin red / sin permiso afectan a todo el chat (aviso fijo, campo).
    if (e != null) _registrarError(e);
  }

  void _bajarAlFinal({bool forzar = false}) {
    if (!forzar && !_seguirAlFinal) return;
    _seguirAlFinal = true;
    _saltarAlFinal(5, _arrastres);
  }

  /// Con alturas distintas (burbujas, separadores de dia) maxScrollExtent
  /// es una ESTIMACION hasta que se dibujan los ultimos elementos: un solo
  /// salto puede quedar unos pixeles antes del final. Se repite en los
  /// frames siguientes hasta llegar (con tope de [intentos]).
  ///
  /// Si el usuario arrastra la lista entre un salto y el siguiente (subio a
  /// leer), se corta: nunca se lo arrastra de vuelta al final. Las
  /// correcciones que Flutter hace solo al recalcular alturas NO lo cortan.
  void _saltarAlFinal(int intentos, int arrastresAlEmpezar) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients || _arrastres != arrastresAlEmpezar) return;
      final pos = _scroll.position;
      if (pos.pixels >= pos.maxScrollExtent) return;
      _scroll.jumpTo(pos.maxScrollExtent);
      if (intentos > 1) _saltarAlFinal(intentos - 1, arrastresAlEmpezar);
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
            Text(widget.projectName, style: Theme.of(context).textTheme.bodySmall, overflow: TextOverflow.ellipsis),
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
          child: Stack(
            children: [
              Positioned.fill(
                child: GestureDetector(
                  behavior: HitTestBehavior.translucent,
                  excludeFromSemantics: true,
                  onTap: _foco.unfocus,
                  child: _lista(),
                ),
              ),
              if (_nuevosSinVer > 0)
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: 12,
                  child: Center(
                    child: _BotonNuevos(cantidad: _nuevosSinVer, onPressed: _irAlFinal),
                  ),
                ),
            ],
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
    _alturaSeparador = (MediaQuery.textScalerOf(context).scale(12) * 1.5 + 16).ceilToDouble();
    _seqLineaDibujo = _seqLinea;
    final (:arriba, :abajo) = _partir(DateTime.now());

    return NotificationListener<ScrollMetricsNotification>(
      // Flutter recalcula el alto total (estimado) a medida que dibuja
      // elementos de alturas distintas, o cuando aparece la linea "Mensajes
      // nuevos". Si estabas al final, la vista se queda al final; si
      // subiste a leer (_seguirAlFinal false), no se toca.
      onNotification: (n) {
        final m = n.metrics;
        if (_seguirAlFinal && m.pixels < m.maxScrollExtent) _saltarAlFinal(1, _arrastres);
        return false;
      },
      child: NotificationListener<ScrollStartNotification>(
        onNotification: (n) {
          if (n.dragDetails != null) _arrastres++;
          return false;
        },
        child: CustomScrollView(
          controller: _scroll,
          center: _claveCentro,
          // Deslizar la lista tambien baja el teclado.
          keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
          slivers: [
            // Lo de antes del centro se dibuja hacia ARRIBA: este va en el tope.
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
              sliver: SliverToBoxAdapter(child: _cabeceraHistorial()),
            ),
            // Hacia arriba: el indice 0 es el mas cercano al centro (la fila mas
            // nueva de las viejas), por eso se recorre al reves.
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              sliver: SliverList.builder(
                itemCount: arriba.length,
                itemBuilder: (context, i) => _fila(arriba[arriba.length - 1 - i]),
              ),
            ),
            SliverPadding(
              key: _claveCentro,
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
              sliver: SliverList.builder(itemCount: abajo.length, itemBuilder: (context, i) => _fila(abajo[i])),
            ),
          ],
        ),
      ),
    );
  }

  /// Mensajes + pendientes con sus separadores de dia, partidos en el
  /// mismo punto de siempre: lo anterior a la carga inicial va arriba del
  /// centro. El separador del primer elemento de abajo viaja con el.
  ({List<FilaChat<Object>> arriba, List<FilaChat<Object>> abajo}) _partir(DateTime hoy) {
    final ancla = _anclaSeq;
    final filas = intercalarDias<Object>(
      [..._timeline.mensajes, ..._timeline.pendientes],
      (e) => e is ChatMessage ? e.createdAt : (e as MensajePendiente).creado,
      hoy,
    );
    var corte = filas.indexWhere(
      (f) => switch (f) {
        FilaElemento<Object>(:final elemento) =>
          elemento is MensajePendiente || ancla == null || (elemento as ChatMessage).seq >= ancla,
        FilaDia<Object>() => false,
      },
    );
    if (corte < 0) corte = filas.length;
    if (corte > 0 && filas[corte - 1] is FilaDia<Object>) corte--;
    return (arriba: filas.sublist(0, corte), abajo: filas.sublist(corte));
  }

  bool _separadorAlCentro() {
    final abajo = _partir(DateTime.now()).abajo;
    return abajo.isNotEmpty && abajo.first is FilaDia<Object>;
  }

  Widget _fila(FilaChat<Object> fila) => switch (fila) {
    FilaDia<Object>(:final etiqueta) => _SeparadorDia(etiqueta: etiqueta, altura: _alturaSeparador),
    FilaElemento<Object>(:final elemento) =>
      elemento is ChatMessage ? _filaMensaje(elemento) : _filaPendiente(elemento as MensajePendiente),
  };

  Widget _filaPendiente(MensajePendiente p) => _BurbujaPendiente(
    pendiente: p,
    autor: widget.sesion.nombreCompleto,
    onReintentar: () => _publicar(p.clientMessageId),
  );

  Widget _filaMensaje(ChatMessage m) {
    final Widget fila = m.esDeSistema
        ? _MensajeSistema(mensaje: m)
        : _BurbujaMensaje(mensaje: m, esMio: !m.esDeIa && m.authorId == widget.sesion.userId);
    if (m.seq != _seqLineaDibujo) return fila;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [const _LineaNuevos(), fila]);
  }

  /// Arriba de todo: cargando, error con Reintentar, el boton "Cargar
  /// anteriores" (alternativa accesible al deslizar) o el inicio del chat.
  Widget _cabeceraHistorial() {
    final h = _historial;
    if (h.cargando) return const _CargandoAnteriores();
    if (h.ultimoError != null) {
      return _ErrorAnteriores(mensaje: h.ultimoError!.mensaje, onReintentar: _cargarAnteriores);
    }
    if (h.hayMasViejos) {
      return Center(
        child: TextButton.icon(
          onPressed: _cargarAnteriores,
          style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
          icon: const Icon(Icons.history),
          label: const Text('Cargar anteriores'),
        ),
      );
    }
    if (_anclaSeq == null) return const SizedBox.shrink();
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Text(
        'Inicio de la conversación',
        textAlign: TextAlign.center,
        style: TextStyle(fontSize: 12, fontStyle: FontStyle.italic, color: cs.onSurfaceVariant),
      ),
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
                      decoration: InputDecoration(hintText: pista, border: const OutlineInputBorder(), isDense: true),
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
                child: Text('Sin conexión con el servidor.', style: TextStyle(color: cs.onErrorContainer)),
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

/// Linea "Mensajes nuevos" encima del primero que no habias leido.
class _LineaNuevos extends StatelessWidget {
  const _LineaNuevos();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Semantics(
      header: true,
      label: 'Mensajes nuevos',
      child: ExcludeSemantics(
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Row(
            children: [
              Expanded(child: Divider(color: cs.primary, thickness: 1)),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Text(
                  'Mensajes nuevos',
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: cs.primary),
                ),
              ),
              Expanded(child: Divider(color: cs.primary, thickness: 1)),
            ],
          ),
        ),
      ),
    );
  }
}

/// "3 mensajes nuevos ↓": aparece si llegan mensajes mientras lees arriba.
class _BotonNuevos extends StatelessWidget {
  const _BotonNuevos({required this.cantidad, required this.onPressed});

  final int cantidad;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final texto = textoBotonNuevos(cantidad);
    // El propio boton ya se anuncia como boton con su texto y se puede
    // activar con TalkBack; liveRegion avisa cuando aparece o cambia.
    return Semantics(
      liveRegion: true,
      hint: 'Ir al final del chat',
      child: FilledButton.tonalIcon(
        onPressed: onPressed,
        style: FilledButton.styleFrom(minimumSize: const Size(48, 48)),
        icon: const Icon(Icons.arrow_downward),
        label: Text(texto),
      ),
    );
  }
}

/// "Hoy", "Ayer" o "5 nov 2026": centrado, discreto y de altura fija.
class _SeparadorDia extends StatelessWidget {
  const _SeparadorDia({required this.etiqueta, required this.altura});

  final String etiqueta;
  final double altura;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Semantics(
      header: true,
      label: 'Día: $etiqueta',
      child: ExcludeSemantics(
        child: SizedBox(
          height: altura,
          child: Center(
            child: DecoratedBox(
              decoration: BoxDecoration(color: cs.surfaceContainerHighest, borderRadius: BorderRadius.circular(12)),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
                child: Text(etiqueta, maxLines: 1, style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _CargandoAnteriores extends StatelessWidget {
  const _CargandoAnteriores();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Semantics(
      liveRegion: true,
      label: 'Cargando mensajes anteriores',
      child: ExcludeSemantics(
        child: SizedBox(
          height: 48,
          child: Center(
            child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: cs.primary)),
          ),
        ),
      ),
    );
  }
}

class _ErrorAnteriores extends StatelessWidget {
  const _ErrorAnteriores({required this.mensaje, required this.onReintentar});

  final String mensaje;
  final VoidCallback onReintentar;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Semantics(
      liveRegion: true,
      child: Column(
        children: [
          Text(
            'No se pudieron cargar los mensajes anteriores. $mensaje',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 13, color: cs.error),
          ),
          TextButton(
            onPressed: onReintentar,
            style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
            child: Semantics(
              label: 'Reintentar cargar mensajes anteriores',
              excludeSemantics: true,
              child: const Text('Reintentar'),
            ),
          ),
        ],
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
    final encabezado = m.esDeIa ? (m.addressedToName != null ? 'IA · para ${m.addressedToName}' : 'IA') : m.authorName;
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
                child: Semantics(
                  label: 'Reintentar enviar el mensaje',
                  excludeSemantics: true,
                  child: const Text('Reintentar'),
                ),
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
