/*
 * Copyright 2018-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.connection.lettuce;

import io.lettuce.core.XAddArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.XReadArgs.StreamOffset;
import io.lettuce.core.cluster.api.reactive.RedisClusterReactiveCommands;
import reactor.core.publisher.Flux;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.springframework.data.redis.connection.ReactiveRedisConnection.CommandResponse;
import org.springframework.data.redis.connection.ReactiveRedisConnection.KeyCommand;
import org.springframework.data.redis.connection.ReactiveRedisConnection.NumericResponse;
import org.springframework.data.redis.connection.ReactiveStreamCommands;
import org.springframework.data.redis.connection.ReactiveStreamCommands.GroupCommand.GroupCommandAction;
import org.springframework.data.redis.connection.stream.ByteBufferRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.util.ByteUtils;
import org.springframework.util.Assert;

/**
 * {@link ReactiveStreamCommands} implementation for {@literal Lettuce}.
 *
 * @author Mark Paluch
 * @since 2.2
 */
class LettuceReactiveStreamCommands implements ReactiveStreamCommands {

	private final LettuceReactiveRedisConnection connection;

	/**
	 * Create new {@link LettuceReactiveStreamCommands}.
	 *
	 * @param connection must not be {@literal null}.
	 */
	LettuceReactiveStreamCommands(LettuceReactiveRedisConnection connection) {

		Assert.notNull(connection, "Connection must not be null!");
		this.connection = connection;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.ReactiveStreamCommands#xAck(org.reactivestreams.Publisher)
	 */
	@Override
	public Flux<NumericResponse<AcknowledgeCommand, Long>> xAck(Publisher<AcknowledgeCommand> commands) {

		return connection.execute(cmd -> Flux.from(commands).concatMap(command -> {

			Assert.notNull(command.getKey(), "Key must not be null!");
			Assert.notNull(command.getGroup(), "Group must not be null!");
			Assert.notNull(command.getRecordIds(), "recordIds must not be null!");

			return cmd
					.xack(command.getKey(), ByteUtils.getByteBuffer(command.getGroup()), entryIdsToString(command.getRecordIds()))
					.map(value -> new NumericResponse<>(command, value));
		}));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.ReactiveStreamCommands#xAdd(org.reactivestreams.Publisher)
	 */
	@Override
	public Flux<CommandResponse<AddStreamRecord, RecordId>> xAdd(Publisher<AddStreamRecord> commands) {

		return connection.execute(cmd -> Flux.from(commands).concatMap(command -> {

			Assert.notNull(command.getKey(), "Key must not be null!");
			Assert.notNull(command.getBody(), "Body must not be null!");

			XAddArgs args = new XAddArgs();
			if (!command.getRecord().getId().shouldBeAutoGenerated()) {
				args.id(command.getRecord().getId().getValue());
			}

			return cmd.xadd(command.getKey(), args, command.getBody())
					.map(value -> new CommandResponse<>(command, RecordId.of(value)));
		}));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.ReactiveStreamCommands#xDel(org.reactivestreams.Publisher)
	 */
	@Override
	public Flux<CommandResponse<DeleteCommand, Long>> xDel(Publisher<DeleteCommand> commands) {

		return connection.execute(cmd -> Flux.from(commands).concatMap(command -> {

			Assert.notNull(command.getKey(), "Key must not be null!");
			Assert.notNull(command.getRecordIds(), "recordIds must not be null!");

			return cmd.xdel(command.getKey(), entryIdsToString(command.getRecordIds()))
					.map(value -> new NumericResponse<>(command, value));
		}));
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public Flux<CommandResponse<GroupCommand, String>> xGroup(Publisher<GroupCommand> commands) {

		return connection.execute(cmd -> Flux.from(commands).concatMap(command -> {

			Assert.notNull(command.getKey(), "Key must not be null!");
			Assert.notNull(command.getGroupName(), "GroupName must not be null!");

			if (command.getAction().equals(GroupCommandAction.CREATE)) {

				Assert.notNull(command.getReadOffset(), "ReadOffset must not be null!");

				StreamOffset offset = StreamOffset.from(command.getKey(), command.getReadOffset().getOffset());

				return cmd.xgroupCreate(offset, ByteUtils.getByteBuffer(command.getGroupName()))
						.map(it -> new CommandResponse<>(command, it));
			}

			if (command.getAction().equals(GroupCommandAction.DELETE_CONSUMER)) {

				return cmd
						.xgroupDelconsumer(command.getKey(),
								io.lettuce.core.Consumer.from(ByteUtils.getByteBuffer(command.getGroupName()),
										ByteUtils.getByteBuffer(command.getConsumerName())))
						.map(it -> new CommandResponse<>(command, Boolean.TRUE.equals(it) ? "OK" : "Error"));
			}

			if (command.getAction().equals(GroupCommandAction.DESTROY)) {

				return cmd.xgroupDestroy(command.getKey(), ByteUtils.getByteBuffer(command.getGroupName()))
						.map(it -> new CommandResponse<>(command, Boolean.TRUE.equals(it) ? "OK" : "Error"));
			}

			throw new IllegalArgumentException("Unknown group command " + command.getAction());
		}));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.ReactiveStreamCommands#xLen(org.reactivestreams.Publisher)
	 */
	@Override
	public Flux<NumericResponse<KeyCommand, Long>> xLen(Publisher<KeyCommand> commands) {

		return connection.execute(cmd -> Flux.from(commands).concatMap(command -> {

			Assert.notNull(command.getKey(), "Key must not be null!");

			return cmd.xlen(command.getKey()).map(value -> new NumericResponse<>(command, value));
		}));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.ReactiveStreamCommands#xRange(org.reactivestreams.Publisher)
	 */
	@Override
	public Flux<CommandResponse<RangeCommand, Flux<ByteBufferRecord>>> xRange(Publisher<RangeCommand> commands) {

		return connection.execute(cmd -> Flux.from(commands).map(command -> {

			Assert.notNull(command.getKey(), "Key must not be null!");
			Assert.notNull(command.getRange(), "Range must not be null!");
			Assert.notNull(command.getLimit(), "Limit must not be null!");

			io.lettuce.core.Range<String> lettuceRange = RangeConverter.toRange(command.getRange(), Function.identity());
			io.lettuce.core.Limit lettuceLimit = LettuceConverters.toLimit(command.getLimit());

			return new CommandResponse<>(command, cmd.xrange(command.getKey(), lettuceRange, lettuceLimit)
					.map(it -> StreamRecords.newRecord().in(it.getStream()).withId(it.getId()).ofBuffer(it.getBody())));
		}));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.ReactiveStreamCommands#read(org.reactivestreams.Publisher)
	 */
	@Override
	public Flux<CommandResponse<ReadCommand, Flux<ByteBufferRecord>>> read(Publisher<ReadCommand> commands) {

		return Flux.from(commands).map(command -> {

			Assert.notNull(command.getStreamOffsets(), "StreamOffsets must not be null!");
			Assert.notNull(command.getReadOptions(), "ReadOptions must not be null!");

			StreamReadOptions readOptions = command.getReadOptions();

			if (readOptions.getBlock() != null && readOptions.getBlock() >= 0) {
				return new CommandResponse<>(command, connection.executeDedicated(cmd -> doRead(command, readOptions, cmd)));
			}

			return new CommandResponse<>(command, connection.execute(cmd -> doRead(command, readOptions, cmd)));
		});
	}

	private static Flux<ByteBufferRecord> doRead(ReadCommand command, StreamReadOptions readOptions,
			RedisClusterReactiveCommands<ByteBuffer, ByteBuffer> cmd) {

		StreamOffset<ByteBuffer>[] streamOffsets = toStreamOffsets(command.getStreamOffsets());
		XReadArgs args = StreamConverters.toReadArgs(readOptions);

		if (command.getConsumer() == null) {
			return cmd.xread(args, streamOffsets)
					.map(it -> StreamRecords.newRecord().in(it.getStream()).withId(it.getId()).ofBuffer(it.getBody()));
		}

		io.lettuce.core.Consumer<ByteBuffer> lettuceConsumer = toConsumer(command.getConsumer());

		return cmd.xreadgroup(lettuceConsumer, args, streamOffsets)
				.map(it -> StreamRecords.newRecord().in(it.getStream()).withId(it.getId()).ofBuffer(it.getBody()));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.ReactiveStreamCommands#xRevRange(org.reactivestreams.Publisher)
	 */
	@Override
	public Flux<CommandResponse<RangeCommand, Flux<ByteBufferRecord>>> xRevRange(Publisher<RangeCommand> commands) {

		return connection.execute(cmd -> Flux.from(commands).map(command -> {

			Assert.notNull(command.getKey(), "Key must not be null!");
			Assert.notNull(command.getRange(), "Range must not be null!");
			Assert.notNull(command.getLimit(), "Limit must not be null!");

			io.lettuce.core.Range<String> lettuceRange = RangeConverter.toRange(command.getRange(), Function.identity());
			io.lettuce.core.Limit lettuceLimit = LettuceConverters.toLimit(command.getLimit());

			return new CommandResponse<>(command, cmd.xrevrange(command.getKey(), lettuceRange, lettuceLimit)
					.map(it -> StreamRecords.newRecord().in(it.getStream()).withId(it.getId()).ofBuffer(it.getBody())));
		}));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.ReactiveStreamCommands#xTrim(org.reactivestreams.Publisher)
	 */
	@Override
	public Flux<NumericResponse<KeyCommand, Long>> xTrim(Publisher<TrimCommand> commands) {

		return connection.execute(cmd -> Flux.from(commands).concatMap(command -> {

			Assert.notNull(command.getKey(), "Key must not be null!");
			Assert.notNull(command.getCount(), "Count must not be null!");

			return cmd.xtrim(command.getKey(), command.getCount()).map(value -> new NumericResponse<>(command, value));
		}));
	}

	@SuppressWarnings("unchecked")
	private static <T> StreamOffset<T>[] toStreamOffsets(
			Collection<org.springframework.data.redis.connection.stream.StreamOffset<T>> streams) {

		return streams.stream().map(it -> StreamOffset.from(it.getKey(), it.getOffset().getOffset()))
				.toArray(StreamOffset[]::new);
	}

	private static io.lettuce.core.Consumer<ByteBuffer> toConsumer(Consumer consumer) {

		return io.lettuce.core.Consumer.from(ByteUtils.getByteBuffer(consumer.getGroup()),
				ByteUtils.getByteBuffer(consumer.getName()));
	}

	private static String[] entryIdsToString(List<RecordId> recordIds) {

		if (recordIds.size() == 1) {
			return new String[] { recordIds.get(0).getValue() };
		}

		return recordIds.stream().map(RecordId::getValue).toArray(String[]::new);
	}
}
