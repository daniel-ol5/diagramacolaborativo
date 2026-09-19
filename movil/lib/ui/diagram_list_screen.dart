import 'package:flutter/material.dart';

import '../models/diagram_summary.dart';
import '../models/project.dart';
import '../services/api_service.dart';
import '../services/config_service.dart';
import 'capture_screen.dart';
import 'error_view.dart';

class DiagramListScreen extends StatefulWidget {
  const DiagramListScreen({super.key, required this.sesion, required this.proyecto});

  final Sesion sesion;
  final Project proyecto;

  @override
  State<DiagramListScreen> createState() => _DiagramListScreenState();
}

class _DiagramListScreenState extends State<DiagramListScreen> {
  late final ApiService _api = ApiService(
    direccionServidor: widget.sesion.direccionServidor,
    token: widget.sesion.token,
  );
  Future<List<DiagramSummary>>? _futuro;

  @override
  void initState() {
    super.initState();
    _cargar();
  }

  void _cargar() {
    setState(() => _futuro = _api.listarDiagramas(widget.proyecto.id));
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
    return Scaffold(
      appBar: AppBar(title: Text(widget.proyecto.name)),
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
            return const Center(child: Text('Este proyecto todavía no tiene diagramas.'));
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
