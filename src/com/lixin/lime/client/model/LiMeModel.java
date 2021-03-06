package com.lixin.lime.client.model;

import com.lixin.lime.client.controller.LiMeFarmer;
import com.lixin.lime.client.controller.LiMeKnight;
import com.lixin.lime.protocol.exception.LiMeException;
import com.lixin.lime.protocol.seed.*;
import com.lixin.lime.protocol.util.factory.LiMeExceptionFactory;

import java.io.EOFException;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.lixin.lime.protocol.seed.LiMeSeed.*;
import static com.lixin.lime.protocol.util.factory.MyStaticFactory.*;

/**
 * @author lixin
 */
public class LiMeModel {
    private String host;
    private int port;
    private LiMeFarmer farmer;
    private LiMeKnight knight;

    private Socket socket;
    private ObjectOutputStream oos;
    private ObjectInputStream ois;

    /**
     * CachedThreadPool 是通过 java.util.concurrent.Executors 创建的 ThreadPoolExecutor 实例
     * 这个实例会根据需要，在线程可用时，重用之前构造好的池中线程
     * 这个线程池在执行 大量短生命周期的异步任务时（many short-lived asynchronous task），可以显著提高程序性能
     * 调用 execute 时，可以重用之前已构造的可用线程，如果不存在可用线程，那么会重新创建一个新的线程并将其加入到线程池中
     * 如果线程超过 60 秒还未被使用，就会被中止并从缓存中移除。因此，线程池在长时间空闲后不会消耗任何资源
     */
    private ExecutorService cachedThreadPool;
    private LiMeExceptionFactory exceptionFactory;

    public LiMeModel(String host, int port, LiMeFarmer farmer, LiMeKnight knight) {
        this.host = host;
        this.port = port;
        this.farmer = farmer;
        this.knight = knight;
        cachedThreadPool = Executors.newCachedThreadPool();
        exceptionFactory = new LiMeExceptionFactory();
    }

    public synchronized void connectToServer() throws LiMeException {
        try {
            socket = new Socket(host, port);
            oos = new ObjectOutputStream(socket.getOutputStream());
            ois = new ObjectInputStream(socket.getInputStream());
        } catch (Exception e) {
            throw exceptionFactory.newLiMeException(ERROR_CONNECTION);
        }
    }

    public synchronized boolean login(String username, String password) throws LiMeException {
        screenSeed(sendAndGetSeed(new LiMeSeedLogin(username, encrypt(password))), STATUS_LOGIN_SUCCESS);
        cachedThreadPool.execute(new SeedGrinder());
        return true;
    }

    public synchronized boolean register(String username, String password, String gender, String email) throws LiMeException {
        screenSeed(sendAndGetSeed(new LiMeSeedRegister(username, encrypt(password), gender, email)), STATUS_REGISTER_SUCCESS);
        return true;
    }

    public synchronized void logout(String username) throws LiMeException {
        sendSeed(new LiMeSeedLogout(username));
        // TODO: 停止 SeedGrinder 的所有线程，但不需要重新连接
        // cachedThreadPool.shutdownNow();
    }

    /**
     * Perhaps deprecated
     */
    public synchronized void requsetFriendList(String username) throws LiMeException {
        sendSeed(new LiMeSeedRequest(FRIENDS_UPDATE, username, null));
    }

    public synchronized void sendMessage(String sender, String receiver, String message) throws LiMeException {
        String encryptedTime = encrypt(getLiMeTime());
        String encryptedMessage = encrypt(encrypt(encrypt(message, encryptedTime), sender), receiver);
        sendSeed(new LiMeSeedMessage(sender, receiver, encryptedMessage, encryptedTime));
    }

    public synchronized void sendFile(String sender, String receiver, File file) throws LiMeException {
        // 通过服务器传文件
        sendSeed(new LiMeSeedFile(sender, receiver, file));

        // TODO: 下一个版本
        //  服务器给两个LiMe发对方IP，两者建立独立TCP连接，互相传文件，不通过服务器
        //LiMeSeed seedReturn = sendAndGetSeed(new LiMeSeedRequest(RECEIVER_IP, sender, receiver));
        //screenSeed(seedReturn, RECEIVER_IP);
        //LiMeSeedRespond seedRespond = (LiMeSeedRespond) seedReturn;
        // TODO: code here
    }

    public synchronized void sendRequestForgotPassword(String sender) throws LiMeException {
        sendSeed(new LiMeSeedRequest(FORGOT_PASSWORD, sender, null));
    }

    private synchronized void sendSeed(LiMeSeed seed) throws LiMeException {
        try {
            oos.writeObject(seed);
            oos.flush();
        } catch (Exception e) {
            e.printStackTrace();
            throw exceptionFactory.newLiMeException(ERROR_CONNECTION);
        }
    }

    private synchronized LiMeSeed sendAndGetSeed(LiMeSeed seed) throws LiMeException {
        try {
            oos.writeObject(seed);
            oos.flush();
            return (LiMeSeed) ois.readObject();
        } catch (Exception e) {
            e.printStackTrace();
            throw exceptionFactory.newLiMeException(ERROR_CONNECTION);
        }
    }

    private synchronized void screenSeed(LiMeSeed seed, int action) throws LiMeException {
        if (seed == null) {
            throw exceptionFactory.newLiMeException(ERROR_CONNECTION);
        } else {
            int seedAction = seed.getAction();
            if (seedAction != action) {
                throw exceptionFactory.newLiMeException(seedAction);
            }
        }
    }

    private class SeedGrinder implements Runnable {
        @Override
        public void run() {
            LiMeSeed seed;
            try {
                while ((seed = (LiMeSeed) ois.readObject()) != null) {
                    int action = seed.getAction();
                    switch (action) {
                        case ERROR_ADMIN_BANNED:
                            // 被封号
                            throw exceptionFactory.newLiMeException(ERROR_ADMIN_BANNED);
                        case ERROR_ADMIN_KICKED:
                            // 被踢
                            throw exceptionFactory.newLiMeException(ERROR_ADMIN_KICKED);
                        case MESSAGE:
                            if (seed.getReceiver().equals(LIME_GROUP_CHAT)) {
                                knight.newGroupChat(seed);
                            } else {
                                farmer.newLiMeMessage(seed);
                            }
                            break;
                        case FRIENDS_UPDATE:
                            farmer.newFriendList(seed);
                            break;
                        case FILE:
                            farmer.newLiMeFile(seed);
                            break;
                        default:
                            limeInternalError(this.getClass().getCanonicalName(), String.valueOf(action));
                            break;
                    }
                }
            } catch (LiMeException e) {
                farmer.handleLiMeException(e);
                System.exit(0);
            } catch (EOFException e) {
                farmer.handleLiMeException(exceptionFactory.newLiMeException(ERROR_CONNECTION));
                System.exit(0);
            } catch (Exception e) {
                farmer.handleLiMeException(exceptionFactory.newLiMeException(ERROR_UNKNOWN));
                e.printStackTrace();
                System.exit(0);
            }
        }
    }
}
