package com.jin.myrpc.spirngboot.consumer;

import com.jin.myrpc.spirngboot.cluster.LeastActiveLoadBalance;
import com.jin.myrpc.spirngboot.cluster.RandomLoadBalance;
import com.jin.myrpc.spirngboot.protocol.RpcRequest;
import com.jin.myrpc.spirngboot.registry.URL;
import com.jin.myrpc.spirngboot.registry.ZkRegister;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * @author wangjin
 */
public class RpcProxy {
	
	public static <T> T create(Class<?> clazz){
        //clazz传进来本身就是interface
        MethodProxy proxy = new MethodProxy(clazz);
        Class<?>[] interfaces = clazz.isInterface() ?
                                new Class[]{clazz} :
                                clazz.getInterfaces();
        T result = (T) Proxy.newProxyInstance(clazz.getClassLoader(),interfaces,proxy);
        return result;
    }

	private static class MethodProxy implements InvocationHandler {
		private Class<?> clazz;
		public MethodProxy(Class<?> clazz){
			this.clazz = clazz;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args)  throws Throwable {
			//如果传进来是一个已实现的具体类（本次演示略过此逻辑)
			if (Object.class.equals(method.getDeclaringClass())) {
				try {
					return method.invoke(this, args);
				} catch (Throwable t) {
					t.printStackTrace();
				}
				//如果传进来的是一个接口（核心)
			} else {
				return rpcInvoke(proxy,method, args);
			}
			return null;
		}


		/**
		 * 实现接口的核心方法
		 * @param method
		 * @param args
		 * @return
		 */
		public Object rpcInvoke(Object proxy, Method method, Object[] args){

			//传输协议封装
			RpcRequest msg = new RpcRequest();
			msg.setClassName(this.clazz.getName());
			msg.setMethodName(method.getName());
			msg.setParameters(args);
			msg.setParameterTypes(method.getParameterTypes());

			final RpcProxyHandler consumerHandler = new RpcProxyHandler();
			EventLoopGroup group = new NioEventLoopGroup();
			try {
				URL select = new LeastActiveLoadBalance().select(new ZkRegister().lookup("/"+msg.getClassName()));
				Bootstrap b = new Bootstrap();
				b.group(group)
						.channel(NioSocketChannel.class)
						.option(ChannelOption.TCP_NODELAY, true)
						.handler(new ChannelInitializer<SocketChannel>() {
							@Override
							public void initChannel(SocketChannel ch) throws Exception {
								ChannelPipeline pipeline = ch.pipeline();
								//自定义协议解码器
								pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4));
								//自定义协议编码器
								pipeline.addLast("frameEncoder", new LengthFieldPrepender(4));
								//对象参数类型编码器
								pipeline.addLast("encoder", new ObjectEncoder());
								//对象参数类型解码器
								pipeline.addLast("decoder", new ObjectDecoder(Integer.MAX_VALUE, ClassResolvers.cacheDisabled(null)));
								pipeline.addLast("handler",consumerHandler);
							}
						});

				ChannelFuture future = b.connect(select.getServerAddress(), Integer.parseInt(select.getServerPort())).sync();
				future.channel().writeAndFlush(msg).sync();
				future.channel().closeFuture().sync();
			} catch(Exception e){
				e.printStackTrace();
			}finally {
				group.shutdownGracefully();
			}
			return consumerHandler.getResponse();
		}

	}
}



