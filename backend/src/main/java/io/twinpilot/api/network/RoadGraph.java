package io.twinpilot.api.network;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 무방향 도로 그래프(CSR 인접 배열). 노드·간선 수십만 개 규모에서도 메모리와 탐색 비용이 작다.
 */
public final class RoadGraph {

    /** 간선 원본(DB id와 양 끝 노드 인덱스, 비용). */
    public record Edge(int id, int source, int target, double costS) {
    }

    /** 탐색 출발점: 노드 인덱스, 초기 비용(초), 출발 시설 id. */
    public record Seed(int node, double costS, int originId) {
    }

    /** 다중 출발점 최단경로 결과. 도달할 수 없는 노드는 +∞, origin −1. */
    public record Result(double[] costS, int[] origin) {
    }

    private final long version;
    private final int[] nodeIds;
    private final Map<Integer, Integer> nodeIndex;
    private final List<Edge> edges;
    private final int[] offsets;
    private final int[] neighbors;
    private final double[] weights;

    public RoadGraph(long version, int[] nodeIds, List<Edge> edges) {
        this.version = version;
        this.nodeIds = nodeIds;
        this.edges = edges;
        this.nodeIndex = new HashMap<>(nodeIds.length * 2);
        for (int i = 0; i < nodeIds.length; i++) {
            nodeIndex.put(nodeIds[i], i);
        }
        int n = nodeIds.length;
        int[] degree = new int[n + 1];
        for (Edge e : edges) {
            degree[e.source()]++;
            degree[e.target()]++;
        }
        offsets = new int[n + 1];
        for (int i = 0; i < n; i++) {
            offsets[i + 1] = offsets[i] + degree[i];
        }
        neighbors = new int[offsets[n]];
        weights = new double[offsets[n]];
        int[] fill = Arrays.copyOf(offsets, n);
        for (Edge e : edges) {
            neighbors[fill[e.source()]] = e.target();
            weights[fill[e.source()]++] = e.costS();
            neighbors[fill[e.target()]] = e.source();
            weights[fill[e.target()]++] = e.costS();
        }
    }

    public long version() {
        return version;
    }

    public int nodeCount() {
        return nodeIds.length;
    }

    public List<Edge> edges() {
        return edges;
    }

    public Integer indexOf(int nodeId) {
        return nodeIndex.get(nodeId);
    }

    public int nodeId(int index) {
        return nodeIds[index];
    }

    /** 여러 출발점에서 동시에 퍼지는 Dijkstra. 각 노드에 가장 빨리 도달하는 출발점과 비용을 구한다. */
    public Result shortestFrom(List<Seed> seeds) {
        int n = nodeIds.length;
        double[] dist = new double[n];
        int[] origin = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(origin, -1);
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (Seed s : seeds) {
            if (s.costS() < dist[s.node()]) {
                dist[s.node()] = s.costS();
                origin[s.node()] = s.originId();
                queue.add(new double[]{s.costS(), s.node()});
            }
        }
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int u = (int) top[1];
            if (top[0] > dist[u]) {
                continue;
            }
            for (int k = offsets[u]; k < offsets[u + 1]; k++) {
                int v = neighbors[k];
                double nd = dist[u] + weights[k];
                if (nd < dist[v]) {
                    dist[v] = nd;
                    origin[v] = origin[u];
                    queue.add(new double[]{nd, v});
                }
            }
        }
        return new Result(dist, origin);
    }
}
