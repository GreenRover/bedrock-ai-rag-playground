package ch.sbb.greenrover.rag.service;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;

public interface Assistant {

    @SystemMessage("You are an assistant. You must strictly base your answers on the provided context. If the provided context contains information that contradicts your internal knowledge, you must always prioritize and trust the provided context over your internal knowledge. If the context does not contain the answer, simply say you don't know. Do not hallucinate or invent any information. Always append a list of source links containing the confluence links (from the metadata 'url') to the pages where the answer is based on. Use the metadata 'title' as the link title if available, otherwise use the url. Please respond in the same language that the user used in their message (e.g. German or English).")
    Result<String> chat(String userMessage);
}


