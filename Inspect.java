import java.lang.reflect.Field;
public class Inspect {
    public static void main(String[] args) throws Exception {
        Class<?> clazz = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder$Vertex");
        for (Field f : clazz.getDeclaredFields()) {
            System.out.println(f.getType().getName() + " " + f.getName());
        }
    }
}
