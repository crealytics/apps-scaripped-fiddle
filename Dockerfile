FROM nightscape/docker-sbt

WORKDIR /app
COPY project/build.properties project/*.sbt /app/project/
RUN sbt "; update ; compile"

COPY project/*.scala *.sbt /app/project/
RUN sbt "; update ; compile"

COPY . /app/
RUN sbt stage

CMD ["/app/server/target/universal/stage/bin/server"]
EXPOSE 8080
