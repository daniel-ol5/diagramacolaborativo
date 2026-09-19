/// Espejo de DiagramResponse.java / web/src/access/types.ts (DiagramSummary)
/// -- sin el contenido del modelo canonico, que esta app nunca necesita.
class DiagramSummary {
  const DiagramSummary({
    required this.id,
    required this.projectId,
    required this.name,
  });

  final String id;
  final String projectId;
  final String name;

  factory DiagramSummary.fromJson(Map<String, dynamic> json) => DiagramSummary(
        id: json['id'] as String,
        projectId: json['projectId'] as String,
        name: json['name'] as String,
      );
}
