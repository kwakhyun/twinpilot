package io.twinpilot.api.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * glTF 2.0(.gltf/.glb) 검증·메타데이터 추출기.
 * - GLB 헤더/청크 구조 검증, bufferView·accessor 범위 검증
 * - 메시·프리미티브·정점·삼각형 수, POSITION accessor min/max 기반 바운딩 박스
 * - 3D Tiles 변환 권장 등 경고 생성
 */
@Component
public class GltfInspector {

    private static final int GLB_MAGIC = 0x46546C67; // "glTF"
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;
    private static final Map<Integer, Integer> COMPONENT_SIZES = Map.of(
            5120, 1, 5121, 1, 5122, 2, 5123, 2, 5125, 4, 5126, 4);
    private static final Map<String, Integer> TYPE_SIZES = Map.of(
            "SCALAR", 1, "VEC2", 2, "VEC3", 3, "VEC4", 4, "MAT2", 4, "MAT3", 9, "MAT4", 16);
    private static final Set<String> COMPRESSION_EXT = Set.of("KHR_draco_mesh_compression", "EXT_meshopt_compression");

    private final ObjectMapper mapper;

    public GltfInspector(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record Inspection(String format, Map<String, Object> stats, List<String> warnings) {
    }

    public static class InvalidModelException extends IllegalArgumentException {
        public InvalidModelException(String message) {
            super(message);
        }
    }

    public Inspection inspect(byte[] data, String filename) {
        if (data.length >= 12 && ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == GLB_MAGIC) {
            return inspectGlb(data);
        }
        if (filename != null && filename.toLowerCase().endsWith(".gltf")) {
            JsonNode gltf = parseJson(data);
            List<String> warnings = new ArrayList<>();
            for (JsonNode buffer : gltf.path("buffers")) {
                String uri = buffer.path("uri").asString("");
                if (!uri.startsWith("data:")) {
                    warnings.add("외부 버퍼 참조(" + uri + ")는 함께 업로드되지 않아 렌더링되지 않을 수 있습니다. GLB 사용을 권장합니다.");
                }
            }
            return analyze("gltf", gltf, -1, warnings);
        }
        throw new InvalidModelException("glTF 2.0(.glb/.gltf) 파일이 아닙니다.");
    }

    private Inspection inspectGlb(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.getInt(); // magic
        int version = buf.getInt();
        int length = buf.getInt();
        if (version != 2) {
            throw new InvalidModelException("GLB 버전 2만 지원합니다: " + version);
        }
        if (length != data.length) {
            throw new InvalidModelException("GLB 헤더 길이(%d)와 파일 크기(%d)가 다릅니다.".formatted(length, data.length));
        }
        JsonNode json = null;
        int binLength = -1;
        while (buf.remaining() >= 8) {
            int chunkLength = buf.getInt();
            int chunkType = buf.getInt();
            if (chunkLength < 0 || chunkLength > buf.remaining()) {
                throw new InvalidModelException("GLB 청크 길이가 파일 범위를 벗어납니다.");
            }
            if (chunkType == CHUNK_JSON && json == null) {
                byte[] jsonBytes = new byte[chunkLength];
                buf.get(jsonBytes);
                json = parseJson(jsonBytes);
            } else if (chunkType == CHUNK_BIN && binLength < 0) {
                binLength = chunkLength;
                buf.position(buf.position() + chunkLength);
            } else {
                buf.position(buf.position() + chunkLength);
            }
        }
        if (json == null) {
            throw new InvalidModelException("GLB에 JSON 청크가 없습니다.");
        }
        return analyze("glb", json, binLength, new ArrayList<>());
    }

    private JsonNode parseJson(byte[] bytes) {
        try {
            return mapper.readTree(new String(bytes, StandardCharsets.UTF_8).trim());
        } catch (RuntimeException e) {
            throw new InvalidModelException("glTF JSON 파싱 실패: " + e.getMessage());
        }
    }

    private Inspection analyze(String format, JsonNode gltf, int binLength, List<String> warnings) {
        String version = gltf.path("asset").path("version").asString("");
        if (!version.startsWith("2")) {
            throw new InvalidModelException("glTF asset.version 2.x가 필요합니다: '" + version + "'");
        }
        JsonNode buffers = gltf.path("buffers");
        JsonNode views = gltf.path("bufferViews");
        JsonNode accessors = gltf.path("accessors");

        if (binLength >= 0 && buffers.size() > 0) {
            long declared = buffers.path(0).path("byteLength").asLong();
            if (declared > binLength) {
                throw new InvalidModelException("buffer[0].byteLength(%d)가 BIN 청크(%d)보다 큽니다.".formatted(declared, binLength));
            }
        }
        for (int i = 0; i < views.size(); i++) {
            JsonNode v = views.path(i);
            int bufferIdx = v.path("buffer").asInt();
            long end = v.path("byteOffset").asLong(0) + v.path("byteLength").asLong();
            long bufLen = buffers.path(bufferIdx).path("byteLength").asLong(-1);
            if (bufLen < 0 || end > bufLen) {
                throw new InvalidModelException("bufferView[%d]가 buffer 범위를 벗어납니다.".formatted(i));
            }
        }
        for (int i = 0; i < accessors.size(); i++) {
            JsonNode a = accessors.path(i);
            Integer comp = COMPONENT_SIZES.get(a.path("componentType").asInt());
            Integer comps = TYPE_SIZES.get(a.path("type").asString(""));
            if (comp == null || comps == null) {
                throw new InvalidModelException("accessor[%d]의 componentType/type이 올바르지 않습니다.".formatted(i));
            }
            if (a.has("bufferView")) {
                JsonNode v = views.path(a.path("bufferView").asInt());
                int stride = v.path("byteStride").asInt(comp * comps);
                long need = a.path("byteOffset").asLong(0) + (a.path("count").asLong() - 1) * stride + (long) comp * comps;
                if (a.path("count").asLong() > 0 && need > v.path("byteLength").asLong()) {
                    throw new InvalidModelException("accessor[%d]가 bufferView 범위를 벗어납니다.".formatted(i));
                }
            }
        }

        long vertices = 0;
        long triangles = 0;
        int primitives = 0;
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (JsonNode mesh : gltf.path("meshes")) {
            for (JsonNode prim : mesh.path("primitives")) {
                primitives++;
                JsonNode pos = accessors.path(prim.path("attributes").path("POSITION").asInt(-1));
                long count = pos.path("count").asLong(0);
                vertices += count;
                int mode = prim.path("mode").asInt(4);
                long indexCount = prim.has("indices") ? accessors.path(prim.path("indices").asInt()).path("count").asLong() : count;
                if (mode == 4) {
                    triangles += indexCount / 3;
                } else if (mode == 5 || mode == 6) {
                    triangles += Math.max(0, indexCount - 2);
                }
                if (pos.path("min").size() == 3 && pos.path("max").size() == 3) {
                    for (int a = 0; a < 3; a++) {
                        min[a] = Math.min(min[a], pos.path("min").path(a).asDouble());
                        max[a] = Math.max(max[a], pos.path("max").path(a).asDouble());
                    }
                }
            }
        }

        List<String> extensions = new ArrayList<>();
        gltf.path("extensionsUsed").forEach(e -> extensions.add(e.asString()));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("gltfVersion", version);
        stats.put("generator", gltf.path("asset").path("generator").asString(null));
        stats.put("scenes", gltf.path("scenes").size());
        stats.put("nodes", gltf.path("nodes").size());
        stats.put("meshes", gltf.path("meshes").size());
        stats.put("primitives", primitives);
        stats.put("materials", gltf.path("materials").size());
        stats.put("textures", gltf.path("textures").size());
        stats.put("images", gltf.path("images").size());
        stats.put("animations", gltf.path("animations").size());
        stats.put("vertices", vertices);
        stats.put("triangles", triangles);
        stats.put("extensionsUsed", extensions);
        if (min[0] != Double.MAX_VALUE) {
            stats.put("bboxMin", round(min));
            stats.put("bboxMax", round(max));
            stats.put("sizeM", round(new double[]{max[0] - min[0], max[1] - min[1], max[2] - min[2]}));
        }

        if (gltf.path("nodes").size() > 0) {
            boolean transformed = false;
            for (JsonNode n : gltf.path("nodes")) {
                transformed |= n.has("matrix") || n.has("scale") || n.has("rotation") || n.has("translation");
            }
            if (transformed) {
                warnings.add("노드 변환(matrix/TRS)이 있어 바운딩 박스는 메시 로컬 좌표 기준 근사값입니다.");
            }
        }
        if (extensions.stream().anyMatch(COMPRESSION_EXT::contains)) {
            warnings.add("메시 압축 확장(" + extensions + ")이 사용되어 정점 수는 압축 해제 기준입니다.");
        }
        if (vertices > 1_000_000) {
            warnings.add("정점 %,d개: 웹 렌더링 성능을 위해 3D Tiles 변환(LOD 분할)을 권장합니다.".formatted(vertices));
        }
        return new Inspection(format, stats, warnings);
    }

    private static double[] round(double[] v) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = Math.round(v[i] * 1000) / 1000.0;
        }
        return r;
    }
}
