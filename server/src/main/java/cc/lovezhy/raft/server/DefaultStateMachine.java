package cc.lovezhy.raft.server;

import cc.lovezhy.raft.server.log.DefaultCommand;
import cc.lovezhy.raft.server.utils.KryoUtils;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.google.common.collect.Maps;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Optional;

@ThreadSafe
public class DefaultStateMachine implements StateMachine {

    private final Map<String, Object> map = Maps.newConcurrentMap();

    @Override
    public synchronized boolean apply(DefaultCommand defaultCommand) {
        switch (defaultCommand.getCommandEnum()) {
            case SET: {
                map.put(defaultCommand.getKey(), defaultCommand.getValue());
                return true;
            }
            case REMOVE: {
                map.remove(defaultCommand.getKey());
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized byte[] takeSnapShot() {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        Output output = new Output(byteArrayOutputStream);
        Kryo kryo = KryoUtils.getPool().borrow();
        kryo.writeClassAndObject(output, map);
        output.flush();
        output.close();
        KryoUtils.getPool().release(kryo);
        return byteArrayOutputStream.toByteArray();
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized boolean fromSnapShot(byte[] bytes) {
        map.clear();
        Input input = new Input(bytes);
        Kryo kryo = KryoUtils.getPool().borrow();
        Map<String, String> snapShotMap = (Map<String, String>) kryo.readClassAndObject(input);
        input.close();
        KryoUtils.getPool().release(kryo);
        map.putAll(snapShotMap);
        return true;
    }

    public Optional<String> getValue(String key) {
        return Optional.ofNullable(map.getOrDefault(key, "").toString());
    }

    public Map<String, Object> getMap() {
        return map;
    }
}
