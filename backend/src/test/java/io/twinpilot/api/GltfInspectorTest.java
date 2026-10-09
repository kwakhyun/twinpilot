package io.twinpilot.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import io.twinpilot.api.model.GltfInspector;

class GltfInspectorTest {

    private final GltfInspector inspector = new GltfInspector(JsonMapper.builder().build());

    @Test
    void inspectsGeneratedLandmarkTower() throws Exception {
        byte[] glb = Files.readAllBytes(Path.of("../data/sample-models/landmark-tower.glb"));

        var result = inspector.inspect(glb, "landmark-tower.glb");

        assertThat(result.format()).isEqualTo("glb");
        assertThat(result.stats()).containsEntry("meshes", 5).containsEntry("primitives", 5)
                .containsEntry("vertices", 120L).containsEntry("triangles", 60L);
        assertThat((double[]) result.stats().get("sizeM")).containsExactly(40.0, 230.0, 40.0);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void rejectsNonGltf() {
        assertThatThrownBy(() -> inspector.inspect("hello".getBytes(StandardCharsets.UTF_8), "a.obj"))
                .isInstanceOf(GltfInspector.InvalidModelException.class);
    }

    @Test
    void rejectsTruncatedGlb() throws Exception {
        byte[] glb = Files.readAllBytes(Path.of("../data/sample-models/landmark-tower.glb"));
        byte[] truncated = java.util.Arrays.copyOf(glb, glb.length - 100);

        assertThatThrownBy(() -> inspector.inspect(truncated, "t.glb"))
                .hasMessageContaining("파일 크기");
    }

    @Test
    void rejectsAccessorOutOfRange() {
        String json = """
                {"asset":{"version":"2.0"},"buffers":[{"byteLength":12}],
                 "bufferViews":[{"buffer":0,"byteLength":12}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":10,"type":"VEC3"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]}""";
        assertThatThrownBy(() -> inspector.inspect(glb(json, new byte[12]), "bad.glb"))
                .hasMessageContaining("accessor[0]");
    }

    @Test
    void warnsAboutExternalBuffersInGltf() {
        String json = """
                {"asset":{"version":"2.0"},"buffers":[{"byteLength":36,"uri":"tower.bin"}],
                 "bufferViews":[{"buffer":0,"byteLength":36}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,2,3]}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]}""";
        var result = inspector.inspect(json.getBytes(StandardCharsets.UTF_8), "tower.gltf");

        assertThat(result.stats()).containsEntry("triangles", 1L);
        assertThat(result.warnings()).anyMatch(w -> w.contains("외부 버퍼"));
    }

    private static byte[] glb(String json, byte[] bin) {
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        int jl = (j.length + 3) / 4 * 4;
        int total = 12 + 8 + jl + 8 + bin.length;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546C67).putInt(2).putInt(total);
        b.putInt(jl).putInt(0x4E4F534A).put(j);
        for (int i = j.length; i < jl; i++) {
            b.put((byte) ' ');
        }
        b.putInt(bin.length).putInt(0x004E4942).put(bin);
        return b.array();
    }
}
