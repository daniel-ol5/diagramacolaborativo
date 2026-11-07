import 'package:flutter/material.dart';

import '../chat/no_leidos.dart';
import '../models/chat_read_state.dart';
import '../models/diagram_summary.dart';
import '../models/project.dart';
import '../services/api_service.dart';
import '../services/chat_service.dart';
import '../services/config_service.dart';
import 'capture_screen.dart';
import 'chat_screen.dart';
import 'error_view.dart';

class DiagramListScreen extends StatefulWidget {
  const DiagramListScreen({super.key, required this.sesion, required this.proyecto, this.chatServicio});

  final Sesion sesion;
  final Project proyecto;

  /// Solo para tests; en la app se arma con la Sesion.
  final ChatService? chatServicio;

  @override
  State<DiagramListScreen> createState() => _DiagramListScreenState();
}

class _DiagramListScreenState extends State<DiagramListScreen> {
  late final ApiService _api = ApiService(
    direccionServidor: widget.sesion.direccionServidor,
    token: widget.sesion.token,
  );
  Future<List<DiagramSummary>>? _futuro;

  late final ChatService _chat =
      widget.chatServicio ??
      ChatService(direccionServidor: widget.sesion.direccionServidor, token: widget.sesion.token);

  /// Estado de lectura del chat (CU23) para el contador del icono. Se
  /// actualiza al abrir, al volver del chat y al refrescar; sin sondeo.
  ChatReadState? _lectura;

  @override
  void initState() {
    super.initState();
    _cargar();
  }

  void _cargar() {
    setState(() {
      _futuro = _api.listarDiagramas(widget.proyecto.id);
    });
    _actualizarNoLeidos();
  }

  /// Si falla, no avisa nada: el icono se queda como estaba (sin numero si
  /// nunca se pudo cargar).
  Future<void> _actualizarNoLeidos() async {
    try {
      _recibirLectura(await _chat.fetchReadState(widget.proyecto.id));
    } on ChatException {
      // el contador es accesorio: sin avisos
    }
  }

  void _recibirLectura(ChatReadState estado) {
    if (!mounted) return;
    setState(() => _lectura = masReciente(_lectura, estado));
  }

  Future<void> _abrirChat() async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ChatScreen(
          sesion: widget.sesion,
          projectId: widget.proyecto.id,
          projectName: widget.proyecto.name,
          servicio: widget.chatServicio,
          alCambiarLectura: _recibirLectura,
        ),
      ),
    );
    // Al volver: lo que se leyo en el chat (y lo que llego mientras tanto).
    _actualizarNoLeidos();
  }

  Future<void> _elegir(DiagramSummary diagrama) async {
    await ConfigService.guardarUltimoDestino(UltimoDestino(
      proyectoId: widget.proyecto.id,
      proyectoNombre: widget.proyecto.name,
      diagramaId: diagrama.id,
      diagramaNombre: diagrama.name,
    ));
    if (!mounted) return;
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => CaptureScreen(
          sesion: widget.sesion,
          proyectoNombre: widget.proyecto.name,
          diagrama: diagrama,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final noLeidos = _lectura?.unreadCount ?? 0;
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.proyecto.name),
        actions: [
          IconButton(
            // El numero no se lee aparte: el tooltip ya lo dice completo.
            icon: ExcludeSemantics(
              child: Badge(
                isLabelVisible: textoContador(noLeidos) != null,
                label: Text(textoContador(noLeidos) ?? ''),
                child: const Icon(Icons.chat_bubble_outline),
              ),
            ),
            // El tooltip es tambien lo que lee TalkBack: incluye el numero.
            tooltip: etiquetaIconoChat(noLeidos),
            onPressed: _abrirChat,
          ),
        ],
      ),
      body: FutureBuilder<List<DiagramSummary>>(
        future: _futuro,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return ErrorView(mensaje: '${snapshot.error}', onReintentar: _cargar);
          }
          final diagramas = snapshot.data!;
          if (diagramas.isEmpty) {
            return const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: Text(
                  'Este proyecto todavía no tiene diagramas.\n\nSe crean desde la web, con la misma cuenta.',
                  textAlign: TextAlign.center,
                ),
              ),
            );
          }
          return RefreshIndicator(
            onRefresh: () async => _cargar(),
            child: ListView.builder(
              itemCount: diagramas.length,
              itemBuilder: (context, i) {
                final d = diagramas[i];
                return ListTile(
                  title: Text(d.name),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => _elegir(d),
                );
              },
            ),
          );
        },
      ),
    );
  }
}
