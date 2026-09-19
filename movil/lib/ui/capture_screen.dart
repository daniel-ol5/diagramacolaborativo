import 'package:flutter/material.dart';

import '../models/diagram_summary.dart';
import '../services/config_service.dart';
import 'login_screen.dart';
import 'photo_capture_screen.dart';
import 'project_list_screen.dart';
import 'voice_command_screen.dart';

/// Pantalla principal despues de elegir destino: microfono y camara con
/// direccion de envio, nada mas -- el punto de partida real de un uso
/// tipico ("parado frente a la pizarra"), donde cambiar de diagrama tiene
/// que ser UN toque, no volver a entrar.
class CaptureScreen extends StatelessWidget {
  const CaptureScreen({
    super.key,
    required this.sesion,
    required this.proyectoNombre,
    required this.diagrama,
  });

  final Sesion sesion;
  final String proyectoNombre;
  final DiagramSummary diagrama;

  Future<void> _cambiarDiagrama(BuildContext context) async {
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => ProjectListScreen(sesion: sesion)),
    );
  }

  Future<void> _cerrarSesion(BuildContext context) async {
    await ConfigService.cerrarSesion();
    if (!context.mounted) return;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const LoginScreen()),
      (route) => false,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(diagrama.name),
        actions: [
          IconButton(icon: const Icon(Icons.logout), tooltip: 'Cerrar sesión', onPressed: () => _cerrarSesion(context)),
        ],
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('$proyectoNombre · ${diagrama.name}', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              TextButton.icon(
                onPressed: () => _cambiarDiagrama(context),
                icon: const Icon(Icons.swap_horiz),
                label: const Text('Cambiar de diagrama'),
              ),
              const SizedBox(height: 32),
              FilledButton.icon(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => VoiceCommandScreen(sesion: sesion, diagramaId: diagrama.id),
                  ),
                ),
                icon: const Icon(Icons.mic),
                label: const Text('Dictar un comando'),
              ),
              const SizedBox(height: 16),
              FilledButton.icon(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => PhotoCaptureScreen(sesion: sesion, diagramaId: diagrama.id),
                  ),
                ),
                icon: const Icon(Icons.camera_alt),
                label: const Text('Sacar una foto'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
